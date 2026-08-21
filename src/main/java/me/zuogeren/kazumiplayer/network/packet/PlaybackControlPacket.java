package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务端：手动切集 / 暂停恢复 / 时间调整
 * action: "next"|"prev"|"pause"|"resume"|"seek_forward"|"seek_back"|"seek_goto"
 * value: 切集时=目标集数, seek_forward/back=秒数(正数), seek_goto=毫秒, pause/resume 忽略
 * 处理逻辑见服务端模块 ServerPacketHandlers。
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
}
