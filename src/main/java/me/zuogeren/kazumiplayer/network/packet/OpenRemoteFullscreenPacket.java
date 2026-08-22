package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C: 服务端校验通过，指示客户端直接进入指定屏幕的全屏观影。
 * 处理逻辑见客户端模块 ClientPacketHandlers。
 */
public record OpenRemoteFullscreenPacket(BlockPos screenPos) implements CustomPacketPayload {

    public static final Type<OpenRemoteFullscreenPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "open_remote_fullscreen"));

    public static final StreamCodec<FriendlyByteBuf, OpenRemoteFullscreenPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRemoteFullscreenPacket::screenPos,
            OpenRemoteFullscreenPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
