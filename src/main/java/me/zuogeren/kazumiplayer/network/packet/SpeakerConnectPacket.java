package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C→S: 连接/断开音响与屏幕
 * 处理逻辑见服务端模块 ServerPacketHandlers。
 */
public record SpeakerConnectPacket(BlockPos speakerPos, BlockPos screenPos, UUID screenId, boolean connect) implements CustomPacketPayload {

    public static final Type<SpeakerConnectPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "speaker_connect"));

    public static final StreamCodec<FriendlyByteBuf, SpeakerConnectPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SpeakerConnectPacket::speakerPos,
            BlockPos.STREAM_CODEC, SpeakerConnectPacket::screenPos,
            net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), SpeakerConnectPacket::screenId,
            net.minecraft.network.codec.ByteBufCodecs.BOOL, SpeakerConnectPacket::connect,
            SpeakerConnectPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
