package me.zuogeren.kazumiplayer.network.packet;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 客户端→服务端：当前集播放完毕，请求切换到下一集
 */
public record NextEpisodePacket(BlockPos screenPos) implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<NextEpisodePacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "next_episode"));

    public static final StreamCodec<FriendlyByteBuf, NextEpisodePacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, NextEpisodePacket::screenPos,
            NextEpisodePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(NextEpisodePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos);
            if (!(be instanceof VideoScreenBlockEntity screen)) return;

            String data = screen.getEpisodeData();
            // 无 EpisodeData（play-url 等）：播放完毕直接停止
            if (data.isEmpty()) {
                stopPlaybackAndNotify(sp, screen, packet.screenPos);
                return;
            }
            int idx = screen.getEpisodeIndex() + 1; // next episode
            List<Road> roads = JsonUtil.GSON.fromJson(data,
                new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
            if (roads == null || roads.isEmpty()) {
                stopPlaybackAndNotify(sp, screen, packet.screenPos);
                return;
            }
            Road road = roads.get(0);
            if (idx < 1 || idx > road.data().size()) {
                // 没有下一集：停止播放并通知
                stopPlaybackAndNotify(sp, screen, packet.screenPos);
                return;
            }

            String nextUrl = road.data().get(idx - 1);
            String allData = JsonUtil.GSON.toJson(roads);
            screen.setPlaybackFull(nextUrl, 0, idx, allData);
            UUID sid = screen.getScreenId();
            SyncGroupManager.get().onPlayStart(sp, sid, packet.screenPos, nextUrl);
            // 同步 WatchingPlayers NBT
            var g = SyncGroupManager.get().getGroup(sid);
            if (g != null) {
                screen.setWatchingPlayers(String.join(",", g.players.stream().map(UUID::toString).toList()));
            }
            // 通知所有观看者（包括触发者，因为自动切集没有单独提示）
            String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
            SyncNotificationUtil.broadcastToGroup(sp, packet.screenPos, sid, "自动切换到 " + name);
            LOGGER.info("Auto next episode {}: {}", idx, nextUrl);
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
        var server = ((net.minecraft.server.level.ServerLevel) triggerPlayer.level()).getServer();
        for (UUID pid : watchers) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) PacketDistributor.sendToPlayer(p, new PlayStopPacket(pos));
        }
        // 通知所有观看者
        SyncNotificationUtil.broadcastToGroup(triggerPlayer, pos, sid, "播放已结束");
        LOGGER.info("Playback ended at {} ({} watchers notified)", pos, watchers.size());
    }
}
