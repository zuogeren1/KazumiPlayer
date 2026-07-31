package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.network.packet.SpeakerConnectPacket;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * 服务端网络包处理器：处理所有 C→S 数据包。
 */
public class ServerPacketHandlers implements IServerPacketHandler {

    @Override
    public void handle(CustomPacketPayload packet, IPayloadContext context) {
        if (packet instanceof NextEpisodePacket pkt) {
            handleNextEpisode(pkt, context);
        } else if (packet instanceof PlaybackControlPacket pkt) {
            handlePlaybackControl(pkt, context);
        } else if (packet instanceof SpeakerConnectPacket pkt) {
            handleSpeakerConnect(pkt, context);
        }
    }

    private static void handleNextEpisode(NextEpisodePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;

            String data = screen.getEpisodeData();
            // 无 EpisodeData（play-url 等）：播放完毕直接停止
            if (data.isEmpty()) {
                stopPlaybackAndNotify(sp, screen, packet.screenPos());
                return;
            }
            int idx = screen.getEpisodeIndex() + 1; // next episode
            List<Road> roads = JsonUtil.GSON.fromJson(data,
                new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
            if (roads == null || roads.isEmpty()) {
                stopPlaybackAndNotify(sp, screen, packet.screenPos());
                return;
            }
            Road road = roads.get(0);
            if (idx < 1 || idx > road.data().size()) {
                // 没有下一集：停止播放并通知
                stopPlaybackAndNotify(sp, screen, packet.screenPos());
                return;
            }

            String nextUrl = road.data().get(idx - 1);
            String allData = JsonUtil.GSON.toJson(roads);
            screen.setPlaybackFull(nextUrl, 0, idx, allData);
            UUID sid = screen.getScreenId();
            SyncGroupManager.get().onPlayStart(sp, sid, packet.screenPos(), nextUrl);
            // 同步 WatchingPlayers NBT
            var g = SyncGroupManager.get().getGroup(sid);
            if (g != null) {
                screen.setWatchingPlayers(String.join(",", g.players.stream().map(UUID::toString).toList()));
            }
            // 通知所有观看者（包括触发者，因为自动切集没有单独提示）
            String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
            SyncNotificationUtil.broadcastToGroup(sp, packet.screenPos(), sid, "自动切换到 " + name);
            KazumiLog.network.info("Auto next episode {}: {}", idx, nextUrl);
        });
    }

    private static void stopPlaybackAndNotify(ServerPlayer triggerPlayer, VideoScreenBlockEntity screen, BlockPos pos) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);

        // 保存实时位置到 NBT
        if (g != null) {
            long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
            screen.updateSyncPosition(g.positionMs + elapsed);
        }

        // 收集观看者列表（删组前）
        List<UUID> watchers = g != null ? List.copyOf(g.players) : List.of();

        // 停止播放
        screen.clearPlayback();
        SyncGroupManager.get().leaveByScreenId(sid);

        // 停止所有观看者客户端
        var server = ((ServerLevel) triggerPlayer.level()).getServer();
        for (UUID pid : watchers) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) PacketDistributor.sendToPlayer(p, new PlayStopPacket(pos));
        }
        // 通知所有观看者
        SyncNotificationUtil.broadcastToGroup(triggerPlayer, pos, sid, "播放已结束");
        KazumiLog.network.info("Playback ended at {} ({} watchers notified)", pos, watchers.size());
    }

    private static void handlePlaybackControl(PlaybackControlPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return;

            switch (packet.action()) {
                case "next", "prev" -> handleEpisodeSwitch(screen, packet.action());
                case "seek_forward" -> {
                    long newPos = screen.getSyncPositionMs() + packet.value() * 1000;
                    screen.updateSyncPosition(Math.max(0, newPos));
                }
                case "seek_back" -> {
                    long newPos = screen.getSyncPositionMs() - packet.value() * 1000;
                    screen.updateSyncPosition(Math.max(0, newPos));
                }
                case "seek_goto" -> screen.updateSyncPosition(packet.value());
            }
        });
    }

    private static void handleEpisodeSwitch(VideoScreenBlockEntity screen, String action) {
        String data = screen.getEpisodeData();
        if (data.isEmpty()) return;
        List<Road> roads = JsonUtil.GSON.fromJson(data,
            new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
        if (roads == null || roads.isEmpty()) return;
        Road road = roads.get(0);

        int idx = screen.getEpisodeIndex();
        if ("next".equals(action)) idx++;
        else idx--;
        if (idx < 1 || idx > road.data().size()) return;

        String url = road.data().get(idx - 1);
        screen.setPlaybackFull(url, 0, idx, data);
        KazumiLog.network.info("Episode switch to {}: {}", idx, url);
    }

    private static void handleSpeakerConnect(SpeakerConnectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;

            // 校验两个方块都存在
            var spkBe = sp.level().getBlockEntity(packet.speakerPos());
            if (!(spkBe instanceof SpeakerBlockEntity spk)) return;
            var scrBe = sp.level().getBlockEntity(packet.screenPos());
            if (!(scrBe instanceof VideoScreenBlockEntity screen)) return;

            // 校验 screenId 匹配
            if (!packet.screenId().equals(screen.getScreenId())) return;

            if (packet.connect()) {
                // 建立连接：双向更新
                spk.setLink(packet.screenId(), packet.screenPos());
                screen.addConnectedSpeaker(packet.speakerPos());
                sp.sendSystemMessage(Component.literal("§a音响已连接到屏幕 ("
                    + packet.screenPos().getX() + ", " + packet.screenPos().getY() + ", " + packet.screenPos().getZ() + ")"));
            } else {
                // 断开连接：双向清空
                spk.clearLink();
                screen.removeConnectedSpeaker(packet.speakerPos());
                sp.sendSystemMessage(Component.literal("§e音响已断开连接"));
            }
        });
    }
}
