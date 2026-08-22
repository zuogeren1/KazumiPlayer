package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C: 服务端校验通过，指示客户端打开指定屏幕的播放器 GUI。
 * 处理逻辑见客户端模块 ClientPacketHandlers。
 */
public record OpenRemoteGuiPacket(BlockPos screenPos) implements CustomPacketPayload {

    public static final Type<OpenRemoteGuiPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "open_remote_gui"));

    public static final StreamCodec<FriendlyByteBuf, OpenRemoteGuiPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenRemoteGuiPacket::screenPos,
            OpenRemoteGuiPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
