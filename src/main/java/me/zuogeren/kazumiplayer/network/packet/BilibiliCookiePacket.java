package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C: 服务端下发 B 站登录 Cookie（B 站接口请求由客户端发出，服务端配置需随登录同步）。
 * 空串表示服务端未配置：客户端清空已注入的凭据，回落到未登录的清晰度上限。
 * 处理逻辑见客户端模块 ClientPacketHandlers。
 */
public record BilibiliCookiePacket(String cookie) implements CustomPacketPayload {

    public static final Type<BilibiliCookiePacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "bilibili_cookie"));

    public static final StreamCodec<FriendlyByteBuf, BilibiliCookiePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BilibiliCookiePacket::cookie,
            BilibiliCookiePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
