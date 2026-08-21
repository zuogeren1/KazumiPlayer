package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C→S: GUI 输入的自定义视频链接，请求在指定屏幕播放。
 * 处理逻辑见服务端模块 ServerPacketHandlers（与 /kazumi play-url 同链路）。
 */
public record PlayUrlPacket(BlockPos screenPos, String url) implements CustomPacketPayload {

    public static final Type<PlayUrlPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "play_url"));

    public static final StreamCodec<FriendlyByteBuf, PlayUrlPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlayUrlPacket::screenPos,
            ByteBufCodecs.STRING_UTF8, PlayUrlPacket::url,
            PlayUrlPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
