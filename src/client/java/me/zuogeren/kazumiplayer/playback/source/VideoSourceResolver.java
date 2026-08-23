package me.zuogeren.kazumiplayer.playback.source;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;

import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoState;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 视频源解析与播放编排——对齐 Kazumi lib/pages/video/video_controller.dart 的解析用法。
 *
 * 流程：直链直判 → 租约池获取解析器 → WebView/MCEF 解析 → WaterMedia 播放。
 * 与蓝本的差异仅一处：MC 无"重试"按钮，解析失败自动重试一次。
 */
public final class VideoSourceResolver {

    private static final VideoSourceResolver INSTANCE = new VideoSourceResolver();
    private static final int MAX_ATTEMPTS = 2;

    public static VideoSourceResolver getInstance() {
        return INSTANCE;
    }

    private final VideoSourceResolverPool pool = new VideoSourceResolverPool();
    private volatile boolean poolSized;

    private VideoSourceResolver() {}

    /**
     * 发起播放：同步返回播放器实例供调用方登记（启动快照 seek/pause 依赖立即可用的实例），
     * 解析与起播异步完成。直链直接起播，网页型 URL 走嗅探解析。
     */
    public WaterMediaPlayer beginPlayback(VideoScreenBlockEntity screen, String episodeUrl) {
        sizePoolOnce();
        screen.setVideoState(VideoState.LOADING);
        KazumiLog.sniff.info("[source] begin playback at {} url={}", screen.getBlockPos(), episodeUrl);
        WaterMediaPlayer player = new WaterMediaPlayer();
        // 会话身份：解析是异步的，完成时据此判断"这次启动是否仍然被需要"
        // （条目被 stopAll/remove 置空 player、或被新播放器实例顶替 → 本次结果作废）
        ScreenPlayerManager.ScreenPlayer session = ScreenPlayerManager.get(screen.getBlockPos());

        if (looksLikeDirectVideo(episodeUrl)) {
            KazumiLog.sniff.info("[source] direct video URL, playing without sniffing");
            // .m3u8/.m3u 播放列表直链按 HLS 直播流处理：置直连模式绕过服务端时钟同步
            // （live 无稳定时间轴，同步校正/暂停广播只会干扰缓冲，GUI 时间轴控制随之禁用）
            if (isLivePlaylistUrl(episodeUrl)) {
                session.bypassSync = true;
                KazumiLog.sniff.info("[source] live playlist URL, sync bypassed");
            }
            player.play(episodeUrl);
            screen.setVideoState(VideoState.PLAYING);
            return player;
        }

        resolveWithRetry(screen, episodeUrl, player, session, 0);
        return player;
    }

    /** 停止/拆屏时取消全部在途解析 */
    public void cancelAllResolves() {
        pool.cancelAll();
    }

    /** 取消指定屏幕的在途解析并回收其租约（URL 切换停旧播放器时调用） */
    public void cancelResolve(net.minecraft.core.BlockPos pos) {
        pool.cancel(pos.toString());
    }

    private void resolveWithRetry(VideoScreenBlockEntity screen, String episodeUrl,
            WaterMediaPlayer player, ScreenPlayerManager.ScreenPlayer session, int attempt) {
        String key = screen.getBlockPos().toString();
        // 换集/重播场景：先取消该屏在途解析再取租约
        pool.cancel(key);
        VideoSourceResolverPool.Lease lease = pool.tryAcquire(key);
        if (lease == null) {
            KazumiLog.sniff.warn("[source] resolver pool exhausted, giving up screen {}", key);
            Minecraft.getInstance().execute(() -> {
                screen.setVideoState(VideoState.STOPPED);
                KazumiMessages.chatWarn("已达最大同时嗅探数，无法解析该屏幕视频");
            });
            return;
        }

        long startedAt = System.currentTimeMillis();
        Duration timeout = Duration.ofSeconds(ClientConfig.CONFIG.sniffTimeoutSeconds.get());
        KazumiLog.sniff.info("[source] resolving (attempt {}/{}, timeout={}s, key={})",
            attempt + 1, MAX_ATTEMPTS, timeout.toSeconds(), key);
        // useLegacyParser 接线待办：按来源规则的 useLegacyParser 字段传入（需经 NBT/包协议下发）
        lease.resolve(episodeUrl, false, timeout)
            .whenComplete((v, t) -> pool.release(lease))
            .thenAccept(source -> {
                KazumiLog.sniff.info("[source] resolved video URL: {} ({}ms)",
                    source.url(), System.currentTimeMillis() - startedAt);
                startWhenValid(screen, session, player, source.url());
            })
            .exceptionally(t -> {
                Throwable cause = unwrap(t);
                if (cause instanceof VideoSourceResolveException.Cancelled) {
                    KazumiLog.sniff.debug("[source] resolution cancelled for {}", key);
                    return null;
                }
                if (attempt < MAX_ATTEMPTS - 1 && !lease.isCancelled()) {
                    KazumiLog.sniff.warn("[source] resolve failed ({}), retrying...",
                        String.valueOf(cause.getMessage()));
                    resolveWithRetry(screen, episodeUrl, player, session, attempt + 1);
                    return null;
                }
                failPlayback(screen, cause);
                return null;
            });
    }

    /**
     * 异步解析完成的受守卫起播。解析期间以下任一情况发生即丢弃结果并回收播放器，
     * 否则会产生无人引用的孤儿播放器（音频持续外漏，只能重启游戏才能停掉）：
     * <ul>
     *   <li>已离开世界（level==null，退主菜单场景）</li>
     *   <li>屏幕方块已拆除（isRemoved）</li>
     *   <li>会话失效：注册表条目被 stopAll/remove 置空、或 player 已被新实例顶替（换片/停止后重启）——
     *       同时天然封死"PlayStopPacket 先 stop、解析回调后 play 复活"的队列竞态窗口</li>
     * </ul>
     */
    private void startWhenValid(VideoScreenBlockEntity screen,
            ScreenPlayerManager.ScreenPlayer session, WaterMediaPlayer player, String resolvedUrl) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level == null || screen.isRemoved() || session.player != player) {
                KazumiLog.sniff.debug("[source] discard stale resolution for {} (session no longer valid)",
                        screen.getBlockPos());
                player.stop();
                return;
            }
            player.play(resolvedUrl);
            screen.setVideoState(VideoState.PLAYING);
        });
    }

    private void failPlayback(VideoScreenBlockEntity screen, Throwable cause) {
        String reason;
        if (cause instanceof VideoSourceResolveException.Timeout t) {
            reason = "超时（" + t.getMessage() + "）";
        } else if (cause instanceof VideoSourceResolveException.NotFound) {
            reason = "未能从页面中找到视频源";
        } else {
            reason = String.valueOf(cause.getMessage());
        }
        KazumiLog.sniff.warn("[source] resolution failed: {}", reason);
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level == null) return; // 已离开世界，聊天提示无意义
            screen.setVideoState(VideoState.STOPPED);
            KazumiMessages.chatError("视频源解析失败：" + reason);
        });
    }

    private void sizePoolOnce() {
        if (!poolSized) {
            poolSized = true;
            pool.resize(ClientConfig.CONFIG.maxConcurrentSniffs.get());
            KazumiLog.sniff.debug("[source] resolver pool resized to {}",
                ClientConfig.CONFIG.maxConcurrentSniffs.get());
        }
    }

    /**
     * URL 是否可能为可直接播放的视频（file://、盘符、去 query/fragment 后的视频扩展名）。
     * 移植自旧 PlaybackManager.looksLikeDirectVideo；"/" 开头的网页相对路径不算本地文件。
     */
    private static boolean looksLikeDirectVideo(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        boolean hasDrive = url.length() > 2 && (url.charAt(1) == ':' || url.charAt(1) == '：');
        String path = lower;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        boolean hasVideoExt = path.endsWith(".mp4") || path.endsWith(".mkv")
            || path.endsWith(".m3u8") || path.endsWith(".avi")
            || path.endsWith(".webm") || path.endsWith(".mov");
        return lower.startsWith("file://") || hasDrive || hasVideoExt;
    }

    /**
     * URL 去 query/fragment 后以 .m3u8/.m3u 结尾 → 按 HLS 直播流处理
     * （置 {@link ScreenPlayerManager.ScreenPlayer#bypassSync}，绕过同步与自动切集）。
     */
    private static boolean isLivePlaylistUrl(String url) {
        if (url == null) return false;
        return pointsToPlaylist(url.trim());
    }

    private static boolean pointsToPlaylist(String line) {
        String lower = line.toLowerCase();
        int cut = lower.indexOf('?');
        if (cut >= 0) lower = lower.substring(0, cut);
        cut = lower.indexOf('#');
        if (cut >= 0) lower = lower.substring(0, cut);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException || t instanceof ExecutionException) {
            if (t.getCause() == null) break;
            t = t.getCause();
        }
        return t;
    }
}
