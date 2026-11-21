package me.zuogeren.kazumiplayer.playback;

import me.zuogeren.kazumiplayer.client.KazumiClientMessages;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;

import me.zuogeren.kazumiplayer.client.PlayStateListener;
import net.minecraft.client.Minecraft;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;

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
    /** 预热期静音标志：为 true 时起播后置 0 音量（清晰度无缝切换的后台播放器，交接时由调度器恢复） */
    private volatile boolean silentStart;
    /** MRL 装载轮询次数（每 500ms 一次）：加载期提示与排查用 */
    private volatile int loaderPolls;
    /** 最近一次失败的可读细节（HTTP 状态/引擎消息）；重新 play 时清空 */
    private volatile String failureDetail = "";
    /**
     * 停播释放线程：FFmpeg 的 demux 线程可能正阻塞在网络读取上（实测停止时被阻塞数十秒），
     * player.stop() 同步执行会卡住调用线程（通常是渲染线程）→ 阻塞动作交给此后台线程。
     */
    private static final java.util.concurrent.ExecutorService STOP_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "KazumiPlayer-Stop");
            t.setDaemon(true);
            return t;
        });
    /** 播放失败回调（MRL 确定失败/加载超时/播放器创建失败）：队列容错自动跳过的信号源 */
    private volatile Runnable playFailureListener;
    private final java.util.concurrent.atomic.AtomicBoolean failureFired =
            new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * 失败早于监听登记的暂存标志：MRL 装载跑在独立线程，首轮即确定失败时会赶在主线程
     * {@code attachFailureSkip} 登记之前触发——不留暂存就会把这次失败丢掉（队列不会跳过、
     * 状态也不会转 FAILED），只能等看门狗超时兜底
     */
    private final java.util.concurrent.atomic.AtomicBoolean failureMissed =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 异步解析中（嗅探/服务端代理解析）：用于加载期提示区分"正在解析"与"正在取源" */
    private volatile boolean resolving;
    private final List<PlayStateListener> listeners = new ArrayList<>();

    public void addListener(PlayStateListener l) { listeners.add(l); }
    public void removeListener(PlayStateListener l) { listeners.remove(l); }

    /** 设置播放失败回调；每次 play 生命周期至多触发一次（重新 play 时自动复位） */
    public void setPlayFailureListener(Runnable r) {
        this.playFailureListener = r;
        this.failureFired.set(false);
        // 登记之前已经失败过：补投一次，别让这次失败丢掉
        if (this.failureMissed.compareAndSet(true, false)) firePlayFailure();
    }

    /** 可能在 MRL-Loader 线程触发，回调实现方自行投递主线程 */
    private void firePlayFailure() {
        if (this.playFailureListener == null) {
            this.failureMissed.set(true); // 尚无监听：暂存，登记时补投
            return;
        }
        if (this.failureFired.compareAndSet(false, true)) {
            try {
                this.playFailureListener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 标记"正在异步解析"（嗅探/服务端代理解析）；play() 一进来即清除 */
    public void markResolving() {
        this.resolving = true;
    }

    /** 是否处于异步解析阶段（尚未拿到媒体地址） */
    public boolean isResolving() {
        return this.resolving;
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
        play(videoUrl, false);
    }

    /**
     * @param silent 预热期静音：加载完成即置 0 音量——清晰度无缝切换时新旧播放器会短暂共存，
     *               新播放器必须静音加载，交接完成后由调度器恢复正常音量
     */
    public void play(String videoUrl, boolean silent) {
        closed = false;
        this.silentStart = silent;
        this.resolving = false;
        this.loaderPolls = 0;
        this.failureDetail = "";
        this.failureMissed.set(false);
        String url = normalizeUrl(videoUrl);
        KazumiLog.playback.debug("WaterMedia.play() called url={}", url);
        Minecraft mc = Minecraft.getInstance();
        new Thread(() -> {
            for (int retry = 0; retry < 30; retry++) {
                if (closed) return; // 加载期间被 stop：放弃起播（防孤儿播放器）
                loaderPolls++;
                MRL mrl = PlayerAPI.mrl(url);
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
                if (st == MRL.Status.EXPIRED) {
                    // 链接签名已过期：重试无意义（每次查询都会触发 reload 并清空 sources）
                    KazumiLog.playback.error("MRL expired: {} (normalized: {})", videoUrl, url);
                    failureDetail = "MRL expired";
                    mc.execute(() -> KazumiClientMessages.chatError(
                        net.minecraft.network.chat.Component
                            .translatable("kazumiplayer.msg.playback_link_expired").getString()));
                    firePlayFailure();
                    return;
                }
                if (st == MRL.Status.ERROR || st == MRL.Status.BLOCKED) {
                    Throwable reason = mrl.exception();
                    String raw = reason != null && reason.getMessage() != null
                            ? reason.getMessage() : st.name();
                    if (raw.length() > 120) raw = raw.substring(0, 120) + "…";
                    String detail = raw;
                    failureDetail = detail;
                    KazumiLog.playback.error("MRL load failed ({}): {}", url, detail);
                    mc.execute(() -> KazumiClientMessages.chatError(
                        net.minecraft.network.chat.Component
                            .translatable("kazumiplayer.msg.playback_load_failed", detail).getString()));
                    firePlayFailure();
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            if (closed) return;
            KazumiLog.playback.error("MRL loading timeout: {} (normalized: {})", videoUrl, url);
            failureDetail = "MRL loading timeout";
            mc.execute(() -> KazumiClientMessages.chatError(
                net.minecraft.network.chat.Component
                    .translatable("kazumiplayer.msg.playback_load_timeout").getString()));
            firePlayFailure();
        }, "KazumiPlayer-MRL-Loader").start();
    }

    private void createAndStart(MRL mrl, Minecraft mc) {
        MediaPlayer created = null;
        try {
            KazumiLog.playback.debug("createAndStart creating player");
            // v3 API: 使用 ALEngine (OpenAL) 替代 JSEngine (JavaSound) 以获得空间音频。
            // 引擎必须以 supplier 形式传入且不得跨播放器共享——每次起播各自新建，由播放器持有并随 release() 释放
            created = PlayerAPI.player(mrl,
                () -> PlayerAPI.glEngine(Thread.currentThread(), mc),
                () -> PlayerAPI.alEngine());
            if (created == null) {
                KazumiLog.playback.error("Failed to create player for: {}", mrl.uri());
                failureDetail = "player creation returned null";
                mc.execute(() -> KazumiClientMessages.chatError(
                    net.minecraft.network.chat.Component
                        .translatable("kazumiplayer.msg.playback_create_failed").getString()));
                firePlayFailure();
                return;
            }
            // start() 返回 boolean：被拒绝（已释放/无法执行）时必须当作起播失败，否则会静默停在无画面状态
            if (!created.start()) {
                KazumiLog.playback.error("Player refused to start: {}", mrl.uri());
                failureDetail = "player refused to start";
                releaseQuietly(created);
                mc.execute(() -> KazumiClientMessages.chatError(
                    net.minecraft.network.chat.Component
                        .translatable("kazumiplayer.msg.playback_create_failed").getString()));
                firePlayFailure();
                return;
            }
            player = created;
            if (silentStart) {
                player.volume(0); // 预热期静音：不得与仍在出画的旧播放器同时出声
            } else {
                applyVolumeFromOptions();
            }
            // 播放器初始化可能抢走窗口焦点（如引擎/上下文创建），恢复鼠标捕获
            ClientDisconnectHandler.forceRestoreMouseGrab(Minecraft.getInstance());
            // seek 交给外部 tick 延迟执行（此时 demuxer 尚未就绪）
        } catch (Exception e) {
            KazumiLog.playback.error("Playback failed: {}", e.getMessage());
            failureDetail = String.valueOf(e.getMessage());
            // 构造/起播抛异常时引擎可能已创建：必须释放，否则原生资源泄漏
            if (created != null && created != player) releaseQuietly(created);
            firePlayFailure();
        }
    }

    /** 释放一个尚未登记为当前播放器的实例（起播被拒/构造异常路径）：异常只记日志，不打断失败上报 */
    private static void releaseQuietly(MediaPlayer dying) {
        try {
            dying.release();
        } catch (Throwable t) {
            KazumiLog.playback.warn("Failed to release rejected media player: {}", String.valueOf(t.getMessage()));
        }
    }

    /**
     * 从原版唱片机/音符盒音量滑块读取并应用音量 (0-100)，
     * 乘以客户端配置的默认视频音量 videoVolume 作为总系数。
     */
    public void applyVolumeFromOptions() {
        applyVolumeFromOptions(1f);
    }

    /** 同上并叠加每屏独立系数（volumeScale；静音时传 0） */
    public void applyVolumeFromOptions(float extraScale) {
        if (player == null) return;
        float vol = Minecraft.getInstance().options.getSoundSourceVolume(
            net.minecraft.sounds.SoundSource.RECORDS);
        float configured = me.zuogeren.kazumiplayer.ClientConfig.CONFIG.videoVolume.get().floatValue();
        try {
            player.volume((int) (vol * configured * extraScale * 100));
        } catch (Throwable t) {
            // volume 走 ALEngine.gain() → requireContext()：MC 关闭期 OpenAL 上下文已销毁会抛 wm_err:1004，
            // 此时音量已无意义，吞掉即可（否则每帧刷一条 WARN）
            KazumiLog.playback.debug("Volume apply skipped: {}", String.valueOf(t.getMessage()));
        }
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

    /**
     * 暂停。返回是否真正生效：{@code pause()} 在 Status.WAITING（尚无 run 可 latch）、
     * 已暂停/已结束/已停止/失败时返回 false（加载期则挂起 intent，管线打开时应用）。
     * 调用方必须据此决定是否更新自己的状态基线——否则会误以为已应用而永不重试。
     */
    public boolean pause() {
        if (player == null) {
            pendingPause = true;
            return false;
        }
        if (player.pause()) {
            for (var l : listeners) l.onPause();
            return true;
        }
        return false;
    }

    /** 恢复播放；返回是否真正生效（{@code resume()} 仅在暂停态返回 true） */
    public boolean resume() {
        if (player == null) {
            pendingPause = false;
            return false;
        }
        if (player.resume()) {
            for (var l : listeners) l.onResume();
            return true;
        }
        return false;
    }

    /**
     * 跳转。{@code seek()} 在源未打开/直播/已释放时返回 false——此时不能当作已生效，
     * 否则同步锚点会以为位置已对齐而不再重试；转存为待应用 seek 交给 tick 重试。
     */
    public void seek(long ms) {
        lastSeekMs = System.currentTimeMillis();
        if (player != null && player.playing() && player.seek(ms)) {
            for (var l : listeners) l.onSeek(ms);
        } else {
            pendingSeekMs = ms;
        }
    }

    /**
     * 停播：立即失效并摘除引用（渲染/音量/tick 当帧就不再触碰该播放器），
     * 再把阻塞的停止与释放交给后台线程——FFmpeg 的 demux 线程可能正阻塞在网络读取上，
     * 若同步等待会卡死调用线程（实测点"停止屏幕"后主线程被卡数十秒）。
     */
    public void stop() {
        closed = true; // 让在途的 MRL 加载线程放弃创建播放器
        pendingSeekMs = -1;
        pendingPause = false;
        final MediaPlayer dying = player;
        player = null; // 先摘引用：主线程不再等待 FFmpeg
        if (dying != null) {
            // 立即静音必须在调用线程做（不能挪进后台）：STOP_EXECUTOR 是单线程，CACHE 模式下前一个
            // release() 可能阻塞数十秒，音频会一直响到它跑完。mute 走 ALEngine.gain() → requireContext()，
            // 非主线程或 MC 关闭期会抛 wm_err:1004，故吞掉（此时静音已无意义）
            try {
                dying.mute(true); // 释放期间不得残留声音
            } catch (Throwable ignored) {}
            // stop 与 release 都在后台串行执行：两者都会等待 FFmpeg 的 demux 线程，
            // 而缓存模式下 demux 还在把整个媒体文件写入磁盘缓存（实测可达数十秒，且不可中断），
            // 一旦放回渲染线程就会重新卡死客户端。实测清理动作实际发生在引擎内部 lifecycle 线程，
            // 调用线程只是等待，故从后台线程调用同样有效。
            STOP_EXECUTOR.execute(() -> {
                try {
                    dying.stop();
                } catch (Throwable t) {
                    KazumiLog.playback.debug("Media player stop skipped: {}", String.valueOf(t.getMessage()));
                }
                try {
                    dying.release();
                } catch (Throwable t) {
                    // 唯一实测触发场景是 MC 关闭：SoundEngine.destroy() → Library.cleanup() → alcDestroyContext
                    // 已销毁 OpenAL 上下文，而 ALEngine.destroy() 首行就 requireContext() → wm_err:1004。
                    // 此时 SFXEngine.release 因 source != 0 把 released 回滚为 false（引擎仍持原生资源），
                    // 但进程即将退出、上下文已不存在，重试与清理都无意义——只记 DEBUG，不刷 WARN。
                    // 正常运行期不会命中：MC 的 OpenAL 上下文是进程级全局的（alcMakeContextCurrent 一次，
                    // 不用 ALC_EXT_thread_local_context），后台线程同样能拿到。
                    KazumiLog.playback.debug("Media player release skipped: {}", String.valueOf(t.getMessage()));
                }
                KazumiLog.playback.debug("Media player released in background");
            });
        }
        for (var l : listeners) {
            try {
                l.onStop();
            } catch (Throwable ignored) {}
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
                // seek 被拒（源未打开/直播/已释放）时保留意图，下一 tick 再试——否则同步锚点会永久错位
                if (!player.seek(pendingSeekMs)) return;
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

    /**
     * 是否已取得可上传的视频帧：纹理与非零尺寸同时就绪＝解码器已出帧。
     * 这是"出画"的唯一硬判据（时钟进入 PLAYING 只代表音频可播，视频帧可能还没解码出来）。
     */
    public boolean hasVideoOutput() {
        return getTextureId() != 0 && getWidth() > 0;
    }

    /** WaterMedia 时钟状态名（WAITING/LOADING/BUFFERING/PLAYING/PAUSED/STOPPED/ENDED/ERROR）；播放器未创建时 NONE */
    public String statusToken() {
        MediaPlayer p = player;
        return p == null ? "NONE" : p.status().name();
    }

    /** MRL 装载轮询次数（加载期提示用） */
    public int getLoaderPolls() { return loaderPolls; }

    /** 最近一次失败的可读细节（无失败为空串） */
    public String getFailureDetail() { return failureDetail; }
}
