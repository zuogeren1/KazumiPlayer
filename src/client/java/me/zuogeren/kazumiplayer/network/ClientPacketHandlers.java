package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.client.ClientRuleCache;
import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.client.gui.GuiClientState;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStartPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import me.zuogeren.kazumiplayer.playback.PlaybackManager;
import me.zuogeren.kazumiplayer.playback.VideoSniffer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
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
        register(PlayStartPacket.class, ClientPacketHandlers::handlePlayStart);
        register(PlayStopPacket.class, ClientPacketHandlers::handlePlayStop);
        register(SyncStatePacket.class, ClientPacketHandlers::handleSyncState);
        register(RuleSyncPacket.class, ClientPacketHandlers::handleRuleSync);
        register(GuiDataPacket.class, ClientPacketHandlers::handleGuiData);
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

    private static void handlePlayStart(PlayStartPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen) {
                var pm = new PlaybackManager();
                // 如果已在播放，先停止旧的
                var old = ScreenPlayerManager.getPlayer(screen.getBlockPos());
                if (old != null) {
                    old.stop();
                }
                pm.stop(screen);
                pm.playUrl(screen, packet.episodeUrl());
                ScreenPlayerManager.setPlayer(screen.getBlockPos(), pm.getWaterMedia());
                ClientDisconnectHandler.trackScreen(screen);
                var sp = ScreenPlayerManager.get(screen.getBlockPos());
                sp.playbackStartedAt = System.currentTimeMillis();
                sp.lastEpisodeUrl = packet.episodeUrl();
                // join 时预置同步位置
                if (packet.seekMs() >= 0) {
                    sp.player.seek(packet.seekMs());
                    if (packet.paused()) sp.player.pause();
                }
            }
        });
    }

    private static void handlePlayStop(PlayStopPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            // 屏幕停止/被破坏时取消在途嗅探，避免浏览器继续空转加载
            VideoSniffer.cancelActiveSniff();
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

    private static void handleSyncState(SyncStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen) {
                var player = ScreenPlayerManager.getPlayer(screen.getBlockPos());
                if (player == null) return;
                var sp = ScreenPlayerManager.get(screen.getBlockPos());
                // 播放器启动稳定期内（8 秒）跳过漂移校正 seek：
                // 刚启动/seek 后播放器时钟未稳定（可能短暂倒退），此时强 seek 会反复打断缓冲导致画面闪烁
                boolean stabilizing = System.currentTimeMillis() - sp.playbackStartedAt < 8000;
                // 计算实际播放位置: 服务端时间戳 + 本地流逝时间
                long elapsed = packet.paused() ? 0 : System.currentTimeMillis() - packet.serverTimestamp();
                long targetPos = packet.positionMs() + elapsed;
                // 周期广播时位置基本一致：仅在漂移超过阈值时 seek，避免每 5 秒无谓跳转
                long drift = Math.abs(player.getTimeMs() - targetPos);
                // seek 后冷却：seek 会导致解码队列清空重缓冲，期间时钟不稳，
                // 若立即按新广播校正会陷入"seek → 落后 → 又 seek"的循环
                boolean seekCooldown = System.currentTimeMillis() - player.getLastSeekMs() < 5000;
                if (!stabilizing && !seekCooldown && drift > 800) {
                    player.seek(targetPos);
                }
                if (packet.paused()) player.pause();
                else player.resume();
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
