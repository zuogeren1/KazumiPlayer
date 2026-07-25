package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.playback.PlaybackManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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

    private static PlaybackManager playback;

    private static PlaybackManager getPlayback() {
        if (playback == null) {
            playback = new PlaybackManager();
        }
        return playback;
    }

    public static void handle(PlayStartPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(packet.screenPos) instanceof VideoScreenBlockEntity screen) {
                var pm = getPlayback();
                // 如果已在播放，先停止旧的
                if (screen.player != null) {
                    screen.player.stop();
                }
                pm.stop(screen);
                pm.playUrl(screen, packet.episodeUrl);
                screen.player = pm.getWaterMedia();
                // join 时预置同步位置
                if (packet.seekMs >= 0) {
                    screen.player.seek(packet.seekMs);
                    if (packet.paused) screen.player.pause();
                }
            }
        });
    }
}
