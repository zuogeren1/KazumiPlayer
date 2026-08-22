package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端: 时钟同步响应。
 * 客户端按 (serverRecv + serverSend) / 2 - (clientSend + clientRecv) / 2
 * 计算钟差 offset：加到客户端 MonoClock 读数上即得服务器单调时间轴的当前值。
 */
public record TimeSyncResponsePacket(
        long clientSendMonotonicMs,
        long serverRecvMonotonicMs,
        long serverSendMonotonicMs) implements CustomPacketPayload {

    public static final Type<TimeSyncResponsePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "time_sync_response"));

    public static final StreamCodec<FriendlyByteBuf, TimeSyncResponsePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, TimeSyncResponsePacket::clientSendMonotonicMs,
                    ByteBufCodecs.VAR_LONG, TimeSyncResponsePacket::serverRecvMonotonicMs,
                    ByteBufCodecs.VAR_LONG, TimeSyncResponsePacket::serverSendMonotonicMs,
                    TimeSyncResponsePacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
