package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.playback.PlaybackManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PlayStartPacket(
        BlockPos screenPos,
        String episodeUrl,
        String ruleName,
        long timestamp) implements CustomPacketPayload {

    public static final Type<PlayStartPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "play_start"));

    public static final StreamCodec<FriendlyByteBuf, PlayStartPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PlayStartPacket::screenPos,
                    ByteBufCodecs.STRING_UTF8, PlayStartPacket::episodeUrl,
                    ByteBufCodecs.STRING_UTF8, PlayStartPacket::ruleName,
                    ByteBufCodecs.VAR_LONG, PlayStartPacket::timestamp,
                    PlayStartPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static final PlaybackManager playback = new PlaybackManager();

    public static void handle(PlayStartPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(packet.screenPos) instanceof VideoScreenBlockEntity screen) {
                playback.playUrl(screen, packet.episodeUrl);
            }
        });
    }
}
