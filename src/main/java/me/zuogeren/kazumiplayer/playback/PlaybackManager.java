package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoState;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * 客户端播放流程编排
 * 接收 URL → MCEF 嗅探(如需要) → WaterMedia 播放
 */
public class PlaybackManager {
    private final WaterMediaPlayer waterMedia = new WaterMediaPlayer();
    private final VideoSniffer sniffer = new VideoSniffer();

    /**
     * 播放 URL（自动判断是否需要 MCEF 嗅探）
     */
    public CompletableFuture<Void> playUrl(VideoScreenBlockEntity screen, String url) {
        screen.setVideoState(VideoState.LOADING);

        // 判断是否需要嗅探：本地文件/已知视频直链直接播，HTML 页面先嗅探
        if (isDirectVideoUrl(url)) {
            Minecraft.getInstance().execute(() -> {
                waterMedia.play(url);
                screen.setVideoState(VideoState.PLAYING);
            });
            return CompletableFuture.completedFuture(null);
        }

        // 用 MCEF 嗅探视频直链
        KazumiLog.playback.info("Sniffing video URL from: {}", url);
        return sniffer.sniff(url)
            .thenAccept(videoUrl -> {
                Minecraft.getInstance().execute(() -> {
                    waterMedia.play(videoUrl);
                    screen.setVideoState(VideoState.PLAYING);
                });
            })
            .exceptionally(e -> {
                // 嗅探失败，尝试直接播放
                KazumiLog.playback.warn("Sniff failed, trying direct play: {}", e.getMessage());
                Minecraft.getInstance().execute(() -> {
                    waterMedia.play(url);
                    screen.setVideoState(VideoState.PLAYING);
                    var mc = Minecraft.getInstance();
                    mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal("§e视频嗅探失败，尝试直接播放..."));
                });
                return null;
            });
    }

    public void stop(VideoScreenBlockEntity screen) {
        waterMedia.stop();
        screen.setVideoState(VideoState.STOPPED);
    }

    public WaterMediaPlayer getWaterMedia() { return waterMedia; }

    private static boolean isDirectVideoUrl(String url) {
        String lower = url.toLowerCase();
        boolean hasDrive = url.length() > 2 && url.charAt(1) == ':';
        boolean hasVideoExt = lower.endsWith(".mp4") || lower.endsWith(".mkv")
            || lower.endsWith(".m3u8") || lower.endsWith(".avi")
            || lower.endsWith(".webm") || lower.endsWith(".mov");
        boolean direct = lower.startsWith("file://") || hasDrive
            || lower.startsWith("/") || hasVideoExt;
        KazumiLog.playback.debug("isDirectVideoUrl urlLen={} char1='{}' drive={} ext={} direct={}",
            url.length(), url.length() > 1 ? url.charAt(1) : '?', hasDrive, hasVideoExt, direct);
        return direct;
    }
}
