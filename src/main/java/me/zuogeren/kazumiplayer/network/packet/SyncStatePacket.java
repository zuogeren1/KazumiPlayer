package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端: 同步播放状态。
 * serverTimestamp 为服务器发送时刻的 MonoClock.millis()（服务器单调毫秒锚点），
 * 客户端经时钟同步握手（ClientClockSync）补偿两端钟差后做锚点插值。
 * forceSeek 为 seek 类操作后的即时广播标记：客户端须无条件跳转到目标位置，
 * 不做漂移阈值判断（±10s 内的小幅位置变化永远够不到兜底阈值，必须显式指令才会生效）。
 * 处理逻辑见客户端模块 ClientPacketHandlers。
 */
public record SyncStatePacket(
        BlockPos screenPos, String videoUrl, long positionMs,
        boolean paused, long serverTimestamp, boolean forceSeek) implements CustomPacketPayload {

    public static final Type<SyncStatePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "sync_state"));

    public static final StreamCodec<FriendlyByteBuf, SyncStatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SyncStatePacket::screenPos,
                    ByteBufCodecs.STRING_UTF8, SyncStatePacket::videoUrl,
                    ByteBufCodecs.VAR_LONG, SyncStatePacket::positionMs,
                    ByteBufCodecs.BOOL, SyncStatePacket::paused,
                    ByteBufCodecs.VAR_LONG, SyncStatePacket::serverTimestamp,
                    ByteBufCodecs.BOOL, SyncStatePacket::forceSeek,
                    SyncStatePacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
