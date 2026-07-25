package me.zuogeren.kazumiplayer.network.packet;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.List;

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
            if (data.isEmpty()) {
                LOGGER.info("No EpisodeData, skipping auto-next");
                return;
            }
            int idx = screen.getEpisodeIndex() + 1; // next episode
            List<Road> roads = JsonUtil.GSON.fromJson(data,
                new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
            if (roads == null || roads.isEmpty()) return;
            Road road = roads.get(0);
            if (idx < 1 || idx > road.data().size()) return; // out of bounds

            String nextUrl = road.data().get(idx - 1);
            String allData = JsonUtil.GSON.toJson(roads);
            screen.setPlaybackFull(nextUrl, 0, idx, allData);
            SyncGroupManager.get().onPlayStart(sp, packet.screenPos, nextUrl);
            LOGGER.info("Auto next episode {}: {}", idx, nextUrl);
        });
    }
}
