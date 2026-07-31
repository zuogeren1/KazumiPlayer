package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.client.ClientRuleCache;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.network.packet.PlayStartPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.ScreenSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import me.zuogeren.kazumiplayer.playback.PlaybackManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端网络包处理器：处理所有 S→C 数据包。
 */
public class ClientPacketHandlers implements IClientPacketHandler {

    @Override
    public void handle(CustomPacketPayload packet, IPayloadContext context) {
        if (packet instanceof PlayStartPacket pkt) {
            handlePlayStart(pkt, context);
        } else if (packet instanceof PlayStopPacket pkt) {
            handlePlayStop(pkt, context);
        } else if (packet instanceof SyncStatePacket pkt) {
            handleSyncState(pkt, context);
        } else if (packet instanceof RuleSyncPacket pkt) {
            handleRuleSync(pkt, context);
        } else if (packet instanceof ScreenSyncPacket) {
            // Phase 3: 更新客户端屏幕状态（占位）
        }
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
                var sp = ScreenPlayerManager.get(screen.getBlockPos());
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
            if (mc.level.getBlockEntity(packet.screenPos()) instanceof VideoScreenBlockEntity screen) {
                var player = ScreenPlayerManager.getPlayer(screen.getBlockPos());
                if (player != null) {
                    player.stop();
                    ScreenPlayerManager.setPlayer(screen.getBlockPos(), null);
                }
                // 清空播放 URL，防止客户端 tick 循环立即重开播放
                screen.clearPlayback();
                ScreenPlayerManager.remove(screen.getBlockPos());
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
                // 计算实际播放位置: 服务端时间戳 + 本地流逝时间
                long elapsed = packet.paused() ? 0 : System.currentTimeMillis() - packet.serverTimestamp();
                long targetPos = packet.positionMs() + elapsed;
                player.seek(targetPos);
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
}
