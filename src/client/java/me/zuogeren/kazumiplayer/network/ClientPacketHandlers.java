package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.client.ClientRuleCache;
import me.zuogeren.kazumiplayer.client.ClientClockSync;
import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.client.gui.GuiClientState;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncResponsePacket;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.client.Minecraft;
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
        register(TimeSyncResponsePacket.class, (pkt, ctx) -> ClientClockSync.handleResponse((TimeSyncResponsePacket) pkt));
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

    private static void handlePlayStop(PlayStopPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            // 屏幕停止/被破坏时取消在途嗅探，避免浏览器继续空转加载
            me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver.getInstance().cancelAllResolves();
            // 屏幕方块可能已被移除（BE 已不存在），直接按位置停止本地播放器
            var player = ScreenPlayerManager.getPlayer(packet.screenPos());
            if (player != null) {
                player.stop();
                ScreenPlayerManager.remove(packet.screenPos());
            }
            // 同步取消跟踪（若屏幕 BE 仍存在则在其上取消）
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity s2) {
                ClientDisconnectHandler.untrackScreen(s2);
            }
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen) {
                // 清空播放 URL，防止客户端 tick 循环立即重开播放
                screen.clearPlayback();
            }
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
                // 锚点插值：target = 快照位置 + (映射到服务器单调钟的当前值 - 锚点时刻)
                // serverTimestamp 为服务器发送时的 MonoClock.millis()，钟差由 ClientClockSync 握手补偿；
                // 时钟未同步时跳过位置校正（只应用暂停状态），避免墙钟偏差造成恒定误差
                long serverNow = ClientClockSync.serverNowMillis();
                long targetPos;
                // 服务端已换片而本地尚未跟进（tick 轮询有 ≤1s 延迟）时禁止兜底 seek 与暂停应用：
                // 否则新片 target≈0 与旧播放器时间相差悬殊，误触发硬 seek 打断即将重建的播放
                boolean switchingEpisode = !sp.lastEpisodeUrl.equals(packet.videoUrl());
                if (serverNow != Long.MIN_VALUE) {
                    long elapsed = packet.paused() ? 0 : Math.max(0, serverNow - packet.serverTimestamp());
                    targetPos = packet.positionMs() + elapsed;
                    boolean seekCooldown =
                            System.currentTimeMillis() - player.getLastSeekMs() < SEEK_COOLDOWN_MS;
                    long drift = Math.abs(player.getTimeMs() - targetPos);
                    if (!seekCooldown && !switchingEpisode && drift > DRIFT_HARD_LIMIT_MS) {
                        KazumiLog.sync.warn("Hard resync at {}: drift {}ms exceeds limit",
                                screen.getBlockPos(), drift);
                        player.seek(targetPos);
                    }
                }
                if (!switchingEpisode) {
                    if (packet.paused()) player.pause();
                    else player.resume();
                }
            }
        });
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
