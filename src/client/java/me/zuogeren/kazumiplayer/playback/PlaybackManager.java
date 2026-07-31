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
                // 嗅探失败：仅当 URL 本身像视频直链时才尝试直接播放；
                // 网页播放页直接喂给播放器只会得到 "Content is not multimedia"
                KazumiLog.playback.warn("Sniff failed: {}", e.getMessage());
                Minecraft.getInstance().execute(() -> {
                    var mc = Minecraft.getInstance();
                    if (looksLikeDirectVideo(url)) {
                        waterMedia.play(url);
                        screen.setVideoState(VideoState.PLAYING);
                        mc.gui.getChat().addClientSystemMessage(
                            net.minecraft.network.chat.Component.literal("§e视频嗅探失败，尝试直接播放..."));
                    } else {
                        screen.setVideoState(VideoState.STOPPED);
                        mc.gui.getChat().addClientSystemMessage(
                            net.minecraft.network.chat.Component.literal("§c视频嗅探失败，未能获取视频直链"));
                    }
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
        return looksLikeDirectVideo(url);
    }

    /** URL 是否可能为可直接播放的视频（file/盘符/视频扩展名），排除网页播放页 */
    private static boolean looksLikeDirectVideo(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        boolean hasDrive = url.length() > 2 && (url.charAt(1) == ':' || url.charAt(1) == '：');
        boolean hasVideoExt = lower.endsWith(".mp4") || lower.endsWith(".mkv")
            || lower.endsWith(".m3u8") || lower.endsWith(".avi")
            || lower.endsWith(".webm") || lower.endsWith(".mov");
        // 注意：不能把 "/xxx" 当作本地文件——网页相对路径（如 /vodplay/x.html）也以 / 开头，
        // 会被误判为直链直接交给播放器导致 "Content is not multimedia"。本地文件由扩展名覆盖。
        boolean direct = lower.startsWith("file://") || hasDrive || hasVideoExt;
        return direct;
    }
}
