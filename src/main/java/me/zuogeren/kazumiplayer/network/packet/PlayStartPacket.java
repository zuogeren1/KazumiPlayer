package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 -> 客户端: 通知客户端开始播放
 */
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
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayStartPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Phase 5: 启动 MCEF 嗅探 + WaterMedia 播放
        });
    }
}
