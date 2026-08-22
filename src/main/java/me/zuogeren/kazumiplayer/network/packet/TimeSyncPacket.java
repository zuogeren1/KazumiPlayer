package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端: 时钟同步探测。
 * clientSendMonotonicMs 为客户端发送时刻的 MonoClock.millis()，
 * 服务端回显并附带收发两端的单调毫秒，供客户端计算钟差 offset。
 */
public record TimeSyncPacket(long clientSendMonotonicMs) implements CustomPacketPayload {

    public static final Type<TimeSyncPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "time_sync"));

    public static final StreamCodec<FriendlyByteBuf, TimeSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, TimeSyncPacket::clientSendMonotonicMs,
                    TimeSyncPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
