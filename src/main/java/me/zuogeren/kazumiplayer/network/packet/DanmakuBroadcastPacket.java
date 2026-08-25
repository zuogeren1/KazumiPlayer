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
 * S→C: 房间弹幕广播（本协议 v3.2 起唯一弹幕包；发送入口为服务端聊天监听，无 C→S 弹幕包）。
 * positionMs/serverTimestamp 为服务端构造时刻快照，v1 即时上屏模型不用于显示调度，
 * 仅入旁路池审计（时间轴调度模式为未来扩展位，wire 先行避免改格式）。
 * 处理逻辑见客户端模块 ClientPacketHandlers。
 */
public record DanmakuBroadcastPacket(BlockPos screenPos, UUID screenId,
        long positionMs, long serverTimestamp,
        UUID senderUuid, String senderName,
        String text, int colorRgb, DanmakuMode mode) implements CustomPacketPayload {

    public static final Type<DanmakuBroadcastPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "danmaku_broadcast"));

    /** 枚举安全编解码：越界值回落 SCROLL（解码端兜底；构造侧来源恒 SCROLL 常量） */
    private static final StreamCodec<io.netty.buffer.ByteBuf, DanmakuMode> MODE_CODEC =
        ByteBufCodecs.BYTE.map(
            b -> b >= 0 && b < DanmakuMode.values().length
                ? DanmakuMode.values()[b] : DanmakuMode.SCROLL,
            mode -> (byte) mode.ordinal());

    public static final StreamCodec<FriendlyByteBuf, DanmakuBroadcastPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, DanmakuBroadcastPacket::screenPos,
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), DanmakuBroadcastPacket::screenId,
            ByteBufCodecs.VAR_LONG, DanmakuBroadcastPacket::positionMs,
            ByteBufCodecs.VAR_LONG, DanmakuBroadcastPacket::serverTimestamp,
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), DanmakuBroadcastPacket::senderUuid,
            ByteBufCodecs.STRING_UTF8, DanmakuBroadcastPacket::senderName,
            ByteBufCodecs.STRING_UTF8, DanmakuBroadcastPacket::text,
            ByteBufCodecs.INT, DanmakuBroadcastPacket::colorRgb,
            MODE_CODEC, DanmakuBroadcastPacket::mode,
            DanmakuBroadcastPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
