package me.zuogeren.kazumiplayer.network.packet;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 客户端→服务端：手动切集 / 时间调整
 * action: "next"|"prev"|"seek_forward"|"seek_back"|"seek_goto"
 * value: 切集时=目标集数, seek_forward/back=秒数(正数), seek_goto=毫秒
 */
public record PlaybackControlPacket(BlockPos screenPos, String action, long value) implements CustomPacketPayload {

    public static final Type<PlaybackControlPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "playback_control"));

    public static final StreamCodec<FriendlyByteBuf, PlaybackControlPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaybackControlPacket::screenPos,
            ByteBufCodecs.STRING_UTF8, PlaybackControlPacket::action,
            ByteBufCodecs.VAR_LONG, PlaybackControlPacket::value,
            PlaybackControlPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(PlaybackControlPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var be = sp.level().getBlockEntity(packet.screenPos);
            if (!(be instanceof VideoScreenBlockEntity screen)) return;

            switch (packet.action) {
                case "next", "prev" -> handleEpisodeSwitch(screen, packet.action);
                case "seek_forward" -> {
                    long newPos = screen.getSyncPositionMs() + packet.value * 1000;
                    screen.updateSyncPosition(Math.max(0, newPos));
                }
                case "seek_back" -> {
                    long newPos = screen.getSyncPositionMs() - packet.value * 1000;
                    screen.updateSyncPosition(Math.max(0, newPos));
                }
                case "seek_goto" -> screen.updateSyncPosition(packet.value);
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
}
