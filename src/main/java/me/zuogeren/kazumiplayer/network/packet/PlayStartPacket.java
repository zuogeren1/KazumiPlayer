package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端: 触发指定屏幕开始播放。
 * 处理逻辑见客户端模块 ClientPacketHandlers。
 */
public record PlayStartPacket(
        BlockPos screenPos,
        String episodeUrl,
        String ruleName,
        long timestamp,
        long seekMs,
        boolean paused) implements CustomPacketPayload {

    public static final Type<PlayStartPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "play_start"));

    public static final StreamCodec<FriendlyByteBuf, PlayStartPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PlayStartPacket::screenPos,
                    ByteBufCodecs.STRING_UTF8, PlayStartPacket::episodeUrl,
                    ByteBufCodecs.STRING_UTF8, PlayStartPacket::ruleName,
                    ByteBufCodecs.VAR_LONG, PlayStartPacket::timestamp,
                    ByteBufCodecs.VAR_LONG, PlayStartPacket::seekMs,
                    ByteBufCodecs.BOOL, PlayStartPacket::paused,
                    PlayStartPacket::new);

    // 不带 seek 的便捷构造（正常播放用）
    public PlayStartPacket(BlockPos screenPos, String episodeUrl, String ruleName, long timestamp) {
        this(screenPos, episodeUrl, ruleName, timestamp, -1, false);
    }

    // 带同步位置的构造（join 用）
    public PlayStartPacket(BlockPos screenPos, String episodeUrl, String ruleName,
                           long timestamp, long seekMs, boolean paused) {
        this.screenPos = screenPos;
        this.episodeUrl = episodeUrl;
        this.ruleName = ruleName;
        this.timestamp = timestamp;
        this.seekMs = seekMs;
        this.paused = paused;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
