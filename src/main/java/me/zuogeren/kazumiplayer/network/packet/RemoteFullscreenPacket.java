package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C→S: 全屏遥控器请求直接进入指定屏幕的全屏观影。
 * 服务端校验屏幕存在（必要时强制加载区块）后回 OpenRemoteFullscreenPacket；
 * 校验失败提示"未找到屏幕方块"。处理逻辑见服务端模块 ServerPacketHandlers。
 */
public record RemoteFullscreenPacket(BlockPos screenPos) implements CustomPacketPayload {

    public static final Type<RemoteFullscreenPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "remote_fullscreen"));

    public static final StreamCodec<FriendlyByteBuf, RemoteFullscreenPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoteFullscreenPacket::screenPos,
            RemoteFullscreenPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
