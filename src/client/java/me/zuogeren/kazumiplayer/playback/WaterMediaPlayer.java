package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;

import me.zuogeren.kazumiplayer.client.PlayStateListener;
import net.minecraft.client.Minecraft;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * WaterMedia V3 播放器封装 (FFmpeg)
 */
public class WaterMediaPlayer {
    private MediaPlayer player;
    private long pendingSeekMs = -1;
    private boolean pendingPause;
    private volatile long lastSeekMs; // 最近一次 seek 的时间戳（漂移校正冷却用）
    /**
     * 会话关闭标志：stop() 置位。play() 的 MRL 加载是异步的（独立线程重试轮询 +
     * mc.execute 二段投递），若加载期间被 stop，必须让加载线程在创建 FFMediaPlayer 前
     * 自行放弃——否则会产生无人引用的孤儿播放器（音频持续外泄且无法停止）。
     */
    private volatile boolean closed;
    /** 播放失败回调（MRL 确定失败/加载超时/播放器创建失败）：队列容错自动跳过的信号源 */
    private volatile Runnable playFailureListener;
    private final java.util.concurrent.atomic.AtomicBoolean failureFired =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final List<PlayStateListener> listeners = new ArrayList<>();

    public void addListener(PlayStateListener l) { listeners.add(l); }
    public void removeListener(PlayStateListener l) { listeners.remove(l); }

    /** 设置播放失败回调；每次 play 生命周期至多触发一次（重新 play 时自动复位） */
    public void setPlayFailureListener(Runnable r) {
        this.playFailureListener = r;
        this.failureFired.set(false);
    }

    /** 可能在 MRL-Loader 线程触发，回调实现方自行投递主线程 */
    private void firePlayFailure() {
        if (this.playFailureListener != null && this.failureFired.compareAndSet(false, true)) {
            try {
                this.playFailureListener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String normalizeUrl(String url) {
        // 本地 Windows/Unix 路径转 file:// URI（正确编码中文等非 ASCII 字符）
        char c1 = url.length() > 1 ? url.charAt(1) : 0;
        if ((c1 == ':' || c1 == '：') || url.startsWith("/") || url.startsWith("~/")) {
            try {
                return java.nio.file.Path.of(url).toUri().toString();
            } catch (Exception ignored) {}
        }
        return url;
    }

    public void play(String videoUrl) {
        closed = false;
        String url = normalizeUrl(videoUrl);
        KazumiLog.playback.debug("WaterMedia.play() called url={}", url);
        Minecraft mc = Minecraft.getInstance();
        new Thread(() -> {
            for (int retry = 0; retry < 30; retry++) {
                if (closed) return; // 加载期间被 stop：放弃起播（防孤儿播放器）
                MRL mrl = MediaAPI.mrl(url);
                if (mrl.source(0) != null) {
                    mc.execute(() -> {
                        if (closed) return; // 与 stop 同在主线程排队，此处检查无竞态
                        createAndStart(mrl, mc);
                    });
                    return;
                }
                // MRL 已进入确定失败态（如 content-type 校验拒绝、平台拦截）：
                // 即时报错收尾，不必傻等满 30 轮超时（典型：IPTV 目录 m3u8 被 text/plain 拒绝）
                MRL.Status st = mrl.status();
                if (st == MRL.Status.ERROR || st == MRL.Status.BLOCKED) {
                    Throwable reason = mrl.exception();
                    String raw = reason != null && reason.getMessage() != null
                            ? reason.getMessage() : st.name();
                    if (raw.length() > 120) raw = raw.substring(0, 120) + "…";
                    String detail = raw;
                    KazumiLog.playback.error("MRL load failed ({}): {}", url, detail);
                    mc.execute(() -> KazumiMessages.chatError("视频加载失败：" + detail));
                    firePlayFailure();
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            if (closed) return;
            KazumiLog.playback.error("MRL loading timeout: {} (normalized: {})", videoUrl, url);
            mc.execute(() -> KazumiMessages.chatError("视频加载超时，请检查网络或稍后重试"));
            firePlayFailure();
        }, "KazumiPlayer-MRL-Loader").start();
    }

    private void createAndStart(MRL mrl, Minecraft mc) {
        try {
            KazumiLog.playback.debug("createAndStart creating player");
            // v3 API: 使用 ALEngine (OpenAL) 替代 JSEngine (JavaSound) 以获得空间音频
            player = MediaAPI.createPlayer(mrl,
                () -> MediaAPI.glEngine(Thread.currentThread(), mc),
                () -> MediaAPI.alEngine());
            if (player == null) {
                KazumiLog.playback.error("Failed to create player for: {}", mrl.uri);
                mc.execute(() -> KazumiMessages.chatError("创建播放器失败"));
                firePlayFailure();
                return;
            }
            player.start();
            applyVolumeFromOptions();
            // 播放器初始化可能抢走窗口焦点（如引擎/上下文创建），恢复鼠标捕获
            ClientDisconnectHandler.forceRestoreMouseGrab(Minecraft.getInstance());
            // seek 交给外部 tick 延迟执行（此时 demuxer 尚未就绪）
        } catch (Exception e) {
            KazumiLog.playback.error("Playback failed: {}", e.getMessage());
            firePlayFailure();
        }
    }

    /** 从原版唱片机/音符盒音量滑块读取并应用音量 (0-100) */
    public void applyVolumeFromOptions() {
        if (player == null) return;
        float vol = Minecraft.getInstance().options.getSoundSourceVolume(
            net.minecraft.sounds.SoundSource.RECORDS);
        player.volume((int) (vol * 100));
    }

    public boolean isPlaying() {
        return player != null && player.playing();
    }

    public boolean isEnded() {
        return player != null && player.ended();
    }

    public long getTextureId() {
        return player != null ? player.texture() : 0;
    }

    /** 当前纹理宽度（解码就绪前可能为 0） */
    public int getWidth() {
        return player != null ? player.width() : 0;
    }

    /** 当前纹理高度（解码就绪前可能为 0） */
    public int getHeight() {
        return player != null ? player.height() : 0;
    }

    public long getTimeMs() {
        return player != null ? player.time() : 0;
    }

    public long getDurationMs() {
        return player != null ? player.duration() : 0;
    }

    public void pause() {
        if (player != null) {
            player.pause();
            for (var l : listeners) l.onPause();
        } else {
            pendingPause = true;
        }
    }

    public void resume() {
        if (player != null) {
            player.resume();
            for (var l : listeners) l.onResume();
        } else {
            pendingPause = false;
        }
    }

    public void seek(long ms) {
        lastSeekMs = System.currentTimeMillis();
        if (player != null && player.playing()) {
            player.seek(ms);
            for (var l : listeners) l.onSeek(ms);
        } else {
            pendingSeekMs = ms;
        }
    }

    public void stop() {
        closed = true; // 让在途的 MRL 加载线程放弃创建播放器
        pendingSeekMs = -1;
        pendingPause = false;
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Throwable t) {
            // 播放器停止异常不能阻断后续清理（否则声音残留且播放器无法复用）
            KazumiLog.playback.warn("Failed to stop media player cleanly: {}", t.getMessage());
        } finally {
            player = null;
            for (var l : listeners) {
                try {
                    l.onStop();
                } catch (Throwable ignored) {}
            }
        }
    }

    public boolean hasPendingSeek() {
        return pendingSeekMs >= 0;
    }

    public void applyPendingSeek() {
        if (player != null && player.playing() && pendingSeekMs >= 0) {
            long now = player.time();
            // 播放器已自然播放到目标附近（如缓冲期间时间推进）→ 跳过重复 seek，避免打断
            if (Math.abs(now - pendingSeekMs) > 500) {
                lastSeekMs = System.currentTimeMillis();
                player.seek(pendingSeekMs);
                KazumiLog.playback.info("Delayed seek: {}ms (time={})", pendingSeekMs, now);
            }
            pendingSeekMs = -1;
            for (var l : listeners) l.onSeek(player.time());
        }
    }

    /** 最近一次 seek 的时间戳（毫秒），0 表示从未 seek 过 */
    public long getLastSeekMs() {
        return lastSeekMs;
    }

    public MediaPlayer getPlayer() { return player; }
}
