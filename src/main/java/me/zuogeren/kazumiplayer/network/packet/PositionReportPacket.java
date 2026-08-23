package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C→S: 客户端首帧锚定上报——新集实际出画后，把播放器真实位置报告给服务端，
 * 服务端据此校准同步组的权威时钟（仅接受每次切集后的第一次上报）。
 * 消除"组时钟从切换意图时刻流逝 vs 播放器经解析/缓冲晚 N 秒出声"造成的假漂移硬 seek。
 * 处理逻辑见服务端模块 ServerPacketHandlers。
 */
public record PositionReportPacket(BlockPos screenPos, UUID screenId, long positionMs) implements CustomPacketPayload {

    public static final Type<PositionReportPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "position_report"));

    public static final StreamCodec<FriendlyByteBuf, PositionReportPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PositionReportPacket::screenPos,
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), PositionReportPacket::screenId,
            ByteBufCodecs.VAR_LONG, PositionReportPacket::positionMs,
            PositionReportPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
