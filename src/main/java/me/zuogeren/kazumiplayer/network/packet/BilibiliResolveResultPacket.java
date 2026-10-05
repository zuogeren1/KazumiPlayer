package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C: 服务端代理解析的结果。
 * 成功时携带有时效的可播放地址（DASH 视频流 / 单流 mp4 / 直播 m3u8）、DASH 的音频从属流地址与档位表；
 * 失败时 ok=false 且 error 为原因（客户端据此决定是否用本地凭据回落解析）。
 * 档位表编码为 "qn|flags|label;..."（label 可能含冒号，故用竖线分隔字段）。
 * audioUrl 非空表示该结果是 DASH：音视频分离，播放时必须把音频挂成从属流。
 */
public record BilibiliResolveResultPacket(BlockPos screenPos, long requestId, String pageUrl,
                                          boolean ok, String url, String audioUrl, String qualities,
                                          int currentQn, String error) implements CustomPacketPayload {

    public static final Type<BilibiliResolveResultPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "bilibili_resolve_result"));

    public static final StreamCodec<FriendlyByteBuf, BilibiliResolveResultPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, BilibiliResolveResultPacket::screenPos,
            ByteBufCodecs.VAR_LONG, BilibiliResolveResultPacket::requestId,
            ByteBufCodecs.STRING_UTF8, BilibiliResolveResultPacket::pageUrl,
            ByteBufCodecs.BOOL, BilibiliResolveResultPacket::ok,
            ByteBufCodecs.STRING_UTF8, BilibiliResolveResultPacket::url,
            ByteBufCodecs.STRING_UTF8, BilibiliResolveResultPacket::audioUrl,
            ByteBufCodecs.STRING_UTF8, BilibiliResolveResultPacket::qualities,
            ByteBufCodecs.VAR_INT, BilibiliResolveResultPacket::currentQn,
            ByteBufCodecs.STRING_UTF8, BilibiliResolveResultPacket::error,
            BilibiliResolveResultPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
