package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务端：当前集播放完毕，请求切换到下一集
 * 处理逻辑见服务端模块 ServerPacketHandlers。
 */
public record NextEpisodePacket(BlockPos screenPos) implements CustomPacketPayload {

    public static final Type<NextEpisodePacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "next_episode"));

    public static final StreamCodec<FriendlyByteBuf, NextEpisodePacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, NextEpisodePacket::screenPos,
            NextEpisodePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
