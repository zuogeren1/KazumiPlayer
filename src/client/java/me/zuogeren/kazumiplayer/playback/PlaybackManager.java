package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.client.BrowserCookieStore;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;

import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoState;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // HTTP 直取播放页解析直链（MacCMS 类站点通用）：java HttpClient 与 MCEF 浏览器
        // 走的网络路径与指纹不同，一方被 CF 拦时另一方常能通；成功则完全跳过浏览器嗅探
        // 带 Referer；若浏览器曾通过该站点的 CF 挑战，成对附加收割的 Cookie + 对应 UA
        String referer = url.replaceAll("^(https?://[^/]+).*$", "$1/");
        var headers = new java.util.HashMap<>(BrowserCookieStore.headersFor(url));
        headers.putIfAbsent("Referer", referer);
        return HttpUtil.fetch(url, "GET", headers, java.util.Map.of())
            .thenApply(PlaybackManager::extractVideoFromHtml)
            // 直取失败（网络异常/非2xx）按"未提取到"处理，转入嗅探流程
            .exceptionally(e -> {
                KazumiLog.playback.debug("HTTP page extract unavailable ({}), falling back to sniff",
                    String.valueOf(e.getMessage()));
                return null;
            })
            .thenCompose(direct -> {
                if (direct != null) {
                    KazumiLog.playback.info("HTTP page extract found video: {}", direct);
                    return playOnClient(screen, direct);
                }
                return startSniffFlow(screen, url);
            });
    }

    /** 播放已解析出的直链（主线程） */
    private CompletableFuture<Void> playOnClient(VideoScreenBlockEntity screen, String videoUrl) {
        CompletableFuture<Void> out = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            waterMedia.play(videoUrl);
            screen.setVideoState(VideoState.PLAYING);
            out.complete(null);
        });
        return out;
    }

    /** 既有 MCEF 嗅探链路（含重试）。MCEF 操作必须在渲染线程，故包一层主线程调度 */
    private CompletableFuture<Void> startSniffFlow(VideoScreenBlockEntity screen, String url) {
        CompletableFuture<Void> out = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            KazumiLog.playback.info("Sniffing video URL from: {}", url);
            sniffer.resetCfChallengeDetection();
            sniffer.sniff(url)
                .thenAccept(videoUrl -> {
                    if (videoUrl == null) return; // 被新嗅探任务接管时的静默收尾，不能 play(null)
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
                        if (looksLikeDirectVideo(url)) {
                            waterMedia.play(url);
                            screen.setVideoState(VideoState.PLAYING);
                            KazumiMessages.chatWarn("视频嗅探失败，尝试直接播放...");
                        } else {
                            // 网页型 URL：Cloudflare 等反爬偶发挑战导致嗅探超时（页面 JS 未执行），
                            // 重试常能通过；常驻浏览器跨重试保留 Cookie
                            retrySniff(screen, url, 0);
                        }
                    });
                    return null;
                })
                .whenComplete((v, t) -> {
                    if (t != null) out.completeExceptionally(t);
                    else out.complete(v);
                });
        });
        return out;
    }

    /**
     * 网页型 URL 嗅探失败后重试（最多 2 次）。
     * Cloudflare 挑战/反爬是概率性的，重试常能通过；常驻浏览器跨重试保留 Cookie，
     * 首次通过挑战后（cf_clearance）后续加载直接放行。
     */
    private void retrySniff(VideoScreenBlockEntity screen, String url, int attempt) {
        if (attempt >= 2) {
            boolean cf = sniffer.isCfChallengeDetected();
            KazumiLog.playback.warn("Sniff failed after retries, cfChallengeDetected={}", cf);
            Minecraft.getInstance().execute(() -> {
                screen.setVideoState(VideoState.STOPPED);
                if (cf) {
                    KazumiMessages.chatError("视频嗅探失败：站点 Cloudflare 防护拦截了浏览器（站点可能不稳定或反爬升级）");
                } else {
                    KazumiMessages.chatError("视频嗅探失败，未能获取视频直链");
                }
            });
            return;
        }
        KazumiLog.playback.warn("Sniff failed (attempt {}), retrying...", attempt + 1);
        sniffer.sniff(url)
            .thenAccept(videoUrl -> Minecraft.getInstance().execute(() -> {
                if (videoUrl != null) {
                    waterMedia.play(videoUrl);
                    screen.setVideoState(VideoState.PLAYING);
                }
            }))
            .exceptionally(err -> {
                Minecraft.getInstance().execute(() -> retrySniff(screen, url, attempt + 1));
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
        // 去掉 query/fragment 后判扩展名——带签名参数的直链（如 CDN 的 .mp4?e=...&deadline=...）
        // 不能因 query 结尾被误判为网页而绕道嗅探
        String path = lower;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        boolean hasVideoExt = path.endsWith(".mp4") || path.endsWith(".mkv")
            || path.endsWith(".m3u8") || path.endsWith(".avi")
            || path.endsWith(".webm") || path.endsWith(".mov");
        // 注意：不能把 "/xxx" 当作本地文件——网页相对路径（如 /vodplay/x.html）也以 / 开头，
        // 会被误判为直链直接交给播放器导致 "Content is not multimedia"。本地文件由扩展名覆盖。
        boolean direct = lower.startsWith("file://") || hasDrive || hasVideoExt;
        return direct;
    }

    // 播放页 HTML 中的视频直链（m3u8/mp4，含带签名 query 的地址）
    private static final Pattern PAGE_VIDEO_URL = Pattern.compile(
        "(?:https?:)?//[^\\s\"'<>\\\\]+?\\.(?:m3u8|mp4)(?:\\?[^\\s\"'<>]*)?",
        Pattern.CASE_INSENSITIVE);

    /**
     * 从播放页 HTML 提取视频直链。
     * MacCMS 类站点（dmbus 等）标准结构：内嵌 player_aaaa={"url":"\/xxx.m3u8",...}
     */
    private static String extractVideoFromHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        String normalized = html.replace("\\/", "/");
        Matcher m = PAGE_VIDEO_URL.matcher(normalized);
        while (m.find()) {
            String u = m.group();
            if (u.contains("prestrain")) continue; // 预加载广告误报
            return u.startsWith("//") ? "https:" + u : u;
        }
        return null;
    }
}
