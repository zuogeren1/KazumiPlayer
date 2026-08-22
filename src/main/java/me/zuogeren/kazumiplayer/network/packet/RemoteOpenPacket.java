package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C→S: 屏幕遥控器请求远程打开指定屏幕的播放器 GUI。
 * 服务端校验屏幕存在（必要时强制加载区块）后回 OpenRemoteGuiPacket；
 * 校验失败提示"未找到屏幕方块"。处理逻辑见服务端模块 ServerPacketHandlers。
 */
public record RemoteOpenPacket(BlockPos screenPos) implements CustomPacketPayload {

    public static final Type<RemoteOpenPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "remote_open"));

    public static final StreamCodec<FriendlyByteBuf, RemoteOpenPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoteOpenPacket::screenPos,
            RemoteOpenPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
