package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.client.ClientRuleCache;
import me.zuogeren.kazumiplayer.client.ClientClockSync;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.client.gui.GuiClientState;
import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.network.packet.BilibiliCookiePacket;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteFullscreenPacket;
import me.zuogeren.kazumiplayer.network.packet.OpenRemoteGuiPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncResponsePacket;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 客户端网络包处理器：处理所有 S→C 数据包。
 * 分派采用注册表（包类型 → 处理方法），新增操作只需 static 块注册一行。
 */
public class ClientPacketHandlers implements IClientPacketHandler {

    private static final Map<Class<?>, BiConsumer<CustomPacketPayload, IPayloadContext>> HANDLERS = new HashMap<>();

    static {
        register(PlayStopPacket.class, ClientPacketHandlers::handlePlayStop);
        register(SyncStatePacket.class, ClientPacketHandlers::handleSyncState);
        register(RuleSyncPacket.class, ClientPacketHandlers::handleRuleSync);
        register(GuiDataPacket.class, ClientPacketHandlers::handleGuiData);
        register(OpenRemoteGuiPacket.class, ClientPacketHandlers::handleOpenRemoteGui);
        register(OpenRemoteFullscreenPacket.class, ClientPacketHandlers::handleOpenRemoteFullscreen);
        register(TimeSyncResponsePacket.class, (pkt, ctx) -> ClientClockSync.handleResponse((TimeSyncResponsePacket) pkt));
        register(BilibiliCookiePacket.class, ClientPacketHandlers::handleBilibiliCookie);
        register(me.zuogeren.kazumiplayer.network.packet.DanmakuBroadcastPacket.class,
            ClientPacketHandlers::handleDanmakuBroadcast);
    }

    private static <T extends CustomPacketPayload> void register(Class<T> cls,
            BiConsumer<T, IPayloadContext> handler) {
        HANDLERS.put(cls, (pkt, ctx) -> handler.accept(cls.cast(pkt), ctx));
    }

    @Override
    public void handle(CustomPacketPayload packet, IPayloadContext context) {
        var h = HANDLERS.get(packet.getClass());
        if (h != null) h.accept(packet, context);
    }

    /**
     * 服务端下发 B 站 Cookie：注入 WaterMedia 平台配置。
     * BiliBiliPlatform 每次请求实时读取 WaterMediaConfig.platforms.biliBiliCookie，
     * 故运行期赋值即时生效（空串 = 未登录，回落到 720P 清晰度上限）。
     */
    private static void handleBilibiliCookie(BilibiliCookiePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                // 自研解析（BilibiliApi）读客户端持有者；直播走 WaterMedia 内置平台，同步注入其配置
                me.zuogeren.kazumiplayer.client.BilibiliCredentials.set(packet.cookie());
                org.watermedia.WaterMediaConfig.platforms.biliBiliCookie = packet.cookie();
                KazumiLog.network.debug("Bilibili cookie applied ({} chars)", packet.cookie().length());
            } catch (Throwable t) {
                KazumiLog.network.warn("Bilibili cookie apply failed: {}", String.valueOf(t.getMessage()));
            }
        });
    }

    /** 屏幕遥控器：服务端已校验屏幕存在，打开对应屏幕的播放器 GUI */
    private static void handleOpenRemoteGui(OpenRemoteGuiPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level != null) {
                Minecraft.getInstance().setScreen(
                    new me.zuogeren.kazumiplayer.client.gui.KazumiPlayerScreen(packet.screenPos()));
            }
        }));
    }

    /** 全屏遥控器：服务端已校验屏幕存在，直接进入全屏观影（退出时不回 GUI） */
    private static void handleOpenRemoteFullscreen(OpenRemoteFullscreenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level != null) {
                me.zuogeren.kazumiplayer.client.ClientFullscreenState.enter(packet.screenPos(), false);
            }
        }));
    }

    private static void handlePlayStop(PlayStopPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            // 屏幕停止/被破坏时取消该屏在途嗅探，避免浏览器继续空转加载
            // （按屏取消：多屏场景下不应波及其他屏幕的在途解析）
            me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver.getInstance()
                .cancelResolve(packet.screenPos());
            // 屏幕方块可能已被移除（BE 已不存在），直接按位置停止本地播放器
            var player = ScreenPlayerManager.getPlayer(packet.screenPos());
            if (player != null) {
                player.stop();
                ScreenPlayerManager.remove(packet.screenPos());
            }
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen) {
                // 清空播放 URL，防止客户端 tick 循环立即重开播放
                screen.clearPlayback();
            }
            // 停止清屏：该屏在途与驻留弹幕一并作废（对即时项幂等双保险）
            me.zuogeren.kazumiplayer.client.danmaku.ClientDanmakuStore.clear(packet.screenPos());
        });
    }

    /** 房间弹幕广播：BE 校验 screenId 一致性后入队统一 Store（即时项，下一帧出队） */
    private static void handleDanmakuBroadcast(me.zuogeren.kazumiplayer.network.packet.DanmakuBroadcastPacket packet,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (!(mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen)) {
                KazumiLog.danmaku.debug("Danmaku dropped: no BE at {}", packet.screenPos());
                return;
            }
            if (!packet.screenId().equals(screen.getScreenId())) {
                KazumiLog.danmaku.debug("Danmaku dropped: screenId mismatch at {} ({} != {})",
                    packet.screenPos(), packet.screenId(), screen.getScreenId());
                return;
            }
            me.zuogeren.kazumiplayer.client.danmaku.ClientDanmakuStore.enqueue(packet.screenPos(),
                new me.zuogeren.kazumiplayer.client.danmaku.DanmakuEntry(
                    packet.text(), packet.colorRgb(), packet.mode(),
                    0L, packet.senderName(), packet.senderUuid(), MonoClock.millis()));
            KazumiLog.danmaku.debug("Danmaku enqueued at {}: [{}] {}",
                packet.screenPos(), packet.senderName(), packet.text());
        });
    }

    // 大幅脱轨兜底 seek 阈值：稳态播放不做周期性校正（seek 会清空解码队列引发卡顿，
    // 且网络流缓冲抖动的秒级时钟波动会被小阈值放大成"重复同步"循环），
    // 仅在错过事件或时钟严重脱轨时硬 seek 重新对齐
    private static final long DRIFT_HARD_LIMIT_MS = 10000;
    /** 兜底 seek 的冷却：seek 后解码队列清空重缓冲，期间时钟不稳不得再次 seek */
    private static final long SEEK_COOLDOWN_MS = 10000;

    private static void handleSyncState(SyncStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen) {
                var player = ScreenPlayerManager.getPlayer(screen.getBlockPos());
                if (player == null) return;
                var sp = ScreenPlayerManager.get(screen.getBlockPos());
                // 直播直连模式（.m3u8/.m3u 直链）：live 流无稳定时间轴，
                // 位置插值/硬 seek 与无条件 pause/resume 均无意义且反复打断缓冲重建，完全绕过
                if (sp.bypassSync) return;
                // 锚点插值：target = 快照位置 + (映射到服务器单调钟的当前值 - 锚点时刻)
                // serverTimestamp 为服务器发送时的 MonoClock.millis()，钟差由 ClientClockSync 握手补偿；
                // 时钟未同步时退回包内快照位置（广播刚发出，误差≈单程延迟，定向 seek 仍可接受）
                long serverNow = ClientClockSync.serverNowMillis();
                long targetPos = packet.positionMs();
                // 服务端已换片而本地尚未跟进（tick 轮询有 ≤1s 延迟）时禁止兜底 seek 与暂停应用：
                // 否则新片 target≈0 与旧播放器时间相差悬殊，误触发硬 seek 打断即将重建的播放
                boolean switchingEpisode = !sp.lastEpisodeUrl.equals(packet.videoUrl());
                if (serverNow != Long.MIN_VALUE) {
                    long elapsed = packet.paused() ? 0 : Math.max(0, serverNow - packet.serverTimestamp());
                    targetPos += elapsed;
                    boolean seekCooldown =
                            System.currentTimeMillis() - player.getLastSeekMs() < SEEK_COOLDOWN_MS;
                    // 启动期保护：切集后组的时钟从切换瞬间起算，而播放器要经解析+缓冲才出声，
                    // 两者天然差数秒——出画（everPlayed）之前不做漂移校正，否则会把新集 seek 到错误位置
                    boolean startingUp = !sp.everPlayed;
                    long drift = Math.abs(player.getTimeMs() - targetPos);
                    // 定向 seek 广播自带跳转指令，不再走漂移兜底（同帧双 seek 会连续清空解码队列）
                    if (!packet.forceSeek() && !seekCooldown && !switchingEpisode
                            && !startingUp && drift > DRIFT_HARD_LIMIT_MS) {
                        long duration = player.getDurationMs();
                        if (duration > 0 && targetPos >= duration) {
                            // 目标位置超出媒体时长：权威状态已被污染（历史教训：墙钟混入 MonoClock 运算），
                            // 拒绝跟随——seek 到天文位置会让 FFmpeg 重开输入失败、管道死亡、误判 ended
                            KazumiLog.sync.error(
                                "Rejected insane sync target {}ms (duration={}ms) at {}",
                                targetPos, duration, screen.getBlockPos());
                        } else {
                            KazumiLog.sync.warn("Hard resync at {}: drift {}ms exceeds limit",
                                screen.getBlockPos(), drift);
                            player.seek(targetPos);
                        }
                    }
                }
                if (!switchingEpisode && packet.forceSeek()) {
                    applyForcedSeek(player, targetPos, screen.getBlockPos());
                }
                if (!switchingEpisode) {
                    // 暂停态变化才应用：周期广播携带相同状态时反复 resume/pause，
                    // 会在 FFmpeg 缓冲饥饿期反复戳播放器内部状态机（日志可见 Pause -> false 刷屏）
                    if (!sp.hasPausedState || sp.lastAppliedPaused != packet.paused()) {
                        sp.hasPausedState = true;
                        sp.lastAppliedPaused = packet.paused();
                        if (packet.paused()) player.pause();
                        else player.resume();
                    }
                }
            }
        });
    }

    /**
     * 定向立即 seek：seek 类操作后的服务端广播要求全组无条件对齐权威位置，
     * 绕过漂移阈值与冷却判断（±10s 内的位移永远够不到兜底阈值）。
     * 播放器未就绪/暂停中时由 pendingSeek 机制在可播放后补跳。
     */
    private static void applyForcedSeek(WaterMediaPlayer player, long targetPos, BlockPos pos) {
        long duration = player.getDurationMs();
        if (duration > 0 && targetPos >= duration) {
            // 与漂移兜底同一污染防护：目标超出媒体时长时拒绝跟随，
            // seek 到天文位置会让 FFmpeg 重开输入失败、管道死亡、误判 ended
            KazumiLog.sync.error(
                "Rejected insane sync target {}ms (duration={}ms) at {}", targetPos, duration, pos);
            return;
        }
        KazumiLog.sync.info("Forced seek to {}ms at {}", targetPos, pos);
        player.seek(targetPos);
    }

    private static void handleRuleSync(RuleSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientRuleCache.updateFromJson(packet.rulesJson());
        });
    }

    /** GUI 通用数据通道：解析后存入 GuiClientState，并推送给已打开的主界面 */
    private static void handleGuiData(GuiDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            GuiClientState.onData(packet.dataType(), packet.payloadJson());
        });
    }
}
