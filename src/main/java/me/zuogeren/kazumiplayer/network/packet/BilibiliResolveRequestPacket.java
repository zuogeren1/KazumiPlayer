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
 * C→S: 请求服务端代为解析 B 站链接（视频页/直播间）。
 * 凭据（Cookie）只留在服务端，避免下发到各客户端；一次解析的结果全组可复用。
 * 客户端在服务端解析失败或超时后可用本地凭据自行回落解析。
 */
public record BilibiliResolveRequestPacket(BlockPos screenPos, UUID screenId, long requestId,
                                           String pageUrl, int preferredQn) implements CustomPacketPayload {

    public static final Type<BilibiliResolveRequestPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "bilibili_resolve_request"));

    public static final StreamCodec<FriendlyByteBuf, BilibiliResolveRequestPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, BilibiliResolveRequestPacket::screenPos,
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), BilibiliResolveRequestPacket::screenId,
            ByteBufCodecs.VAR_LONG, BilibiliResolveRequestPacket::requestId,
            ByteBufCodecs.STRING_UTF8, BilibiliResolveRequestPacket::pageUrl,
            ByteBufCodecs.VAR_INT, BilibiliResolveRequestPacket::preferredQn,
            BilibiliResolveRequestPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
