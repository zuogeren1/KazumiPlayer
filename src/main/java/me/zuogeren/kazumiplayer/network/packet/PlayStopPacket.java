package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端→客户端：停止指定屏幕的播放（仅当前客户端）
 */
public record PlayStopPacket(BlockPos screenPos) implements CustomPacketPayload {

    public static final Type<PlayStopPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "play_stop"));

    public static final StreamCodec<FriendlyByteBuf, PlayStopPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlayStopPacket::screenPos,
            PlayStopPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
