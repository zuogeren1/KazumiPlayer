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
 * C→S: 视频源解析状态上报——观看者客户端在解析（嗅探）开始/完成/失败时向服务端报告，
 * 服务端聚合写入屏幕 BE 的 ResolveStates NBT，经既有 markDirty 通道同步全组，
 * 供 GUI 观看者列表展示"谁还没解析完成"。处理逻辑见服务端模块 ServerPacketHandlers。
 */
public record ResolveStatusPacket(BlockPos screenPos, UUID screenId, int status) implements CustomPacketPayload {

    /** 解析中（已发起嗅探/代理解析，尚未得到可播放地址） */
    public static final int STATUS_RESOLVING = 0;
    /** 已出画（媒体首帧解码并上屏；出画前的加载期一律是 {@link #STATUS_LOADING}） */
    public static final int STATUS_READY = 1;
    /** 解析失败（重试耗尽，或媒体加载/起播失败） */
    public static final int STATUS_FAILED = 2;
    /** 媒体加载中（已拿到可播放地址、播放器已起播，但尚未出画） */
    public static final int STATUS_LOADING = 3;

    public static final Type<ResolveStatusPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "resolve_status"));

    public static final StreamCodec<FriendlyByteBuf, ResolveStatusPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResolveStatusPacket::screenPos,
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), ResolveStatusPacket::screenId,
            ByteBufCodecs.VAR_INT, ResolveStatusPacket::status,
            ResolveStatusPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
