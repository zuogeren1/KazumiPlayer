package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端屏幕播放器注册表（客户端专属）。
 * 每个屏幕方块独立管理 WaterMediaPlayer 及其播放状态，
 * 替代原先存放在 VideoScreenBlockEntity 上的瞬态字段。
 */
public final class ScreenPlayerManager {

    /** 播放位置停滞判定为重缓冲的阈值 */
    public static final long BUFFER_FREEZE_MS = 1500L;

    /** 单个屏幕的客户端播放状态 */
    public static class ScreenPlayer {
        public WaterMediaPlayer player;
        public String lastEpisodeUrl = "";
        public long playbackStartedAt; // 防抖：上次启动播放的时间戳
        public boolean endedNotified;
        /** 本次启动后是否进入过播放态——解析失败/租约放弃的播放器永远为 false，供僵尸自愈判定 */
        public boolean everPlayed;
        /** 本次启动是否已发送首帧锚定上报（PositionReportPacket，每集一次） */
        public boolean anchorReported;
        /**
         * 直播直连模式（.m3u8/.m3u 直链）：绕过服务端时钟同步本地自由播放——
         * live 流没有稳定时间轴，位置校正/暂停广播无意义且干扰缓冲重建，
         * 自动切集兜底会被滑动窗口时长误触发；GUI 的时间轴控制项随之禁用。
         * 新起播前由调度器复位，直播直链起播时置位。
         */
        public boolean bypassSync;
        /** 本屏独立音量系数（0..1），叠加在 RECORDS×videoVolume 总量之上 */
        public float volumeScale = 1f;
        /** 本屏静音（独立于全局音量；解除后回到 volumeScale） */
        public boolean muted;
        /** 缓冲冻结检测：播放位置最近一次推进的时刻（本地墙钟，纯客户端判定） */
        public long lastTimeMs;
        public long lastTimeAdvancedAt;
        /** 最近一次播放失败记录（URL+时刻）：供 GUI/全屏失败横幅展示（20s 窗口） */
        public String lastFailedUrl = "";
        public long lastFailedAt;
        /** 暂停态应用去重基线：hasPausedState=false 表示尚未应用过任何暂停态 */
        public boolean hasPausedState;
        public boolean lastAppliedPaused;
        /** 本集是否已触发下一集预解析（每次新起播复位） */
        public boolean nextPrefetched;
        /**
         * 本端重启播放请求（如 B 站清晰度切换）：调度器停止旧播放器并重新解析起播，
         * 起播后按 {@link #resumePositionMs} 续播。仅本端生效，不影响其他观看者。
         */
        public boolean restartRequested;
        /** 重启后要恢复的播放位置（毫秒，0 表示不续播，回落同步位置） */
        public long resumePositionMs;

        /**
         * 重缓冲冻结判定：已出画、播放中，但播放位置停滞超过阈值。
         * 起播解析期（everPlayed 前）与暂停态天然不在列；直播直连屏时间轴无此语义，调用方另行排除。
         */
        public boolean isBufferingFrozen() {
            return everPlayed && player != null && player.isPlaying()
                && System.currentTimeMillis() - lastTimeAdvancedAt > BUFFER_FREEZE_MS;
        }
    }

    private static final Map<BlockPos, ScreenPlayer> players = new ConcurrentHashMap<>();

    private ScreenPlayerManager() {}

    public static ScreenPlayer get(BlockPos pos) {
        return players.computeIfAbsent(pos, k -> new ScreenPlayer());
    }

    public static WaterMediaPlayer getPlayer(BlockPos pos) {
        ScreenPlayer sp = players.get(pos);
        return sp == null ? null : sp.player;
    }

    /** 切换某屏静音并即时应用到其播放器（无播放器时仅翻状态）；@return 切换后是否静音 */
    public static boolean toggleMuted(BlockPos pos) {
        var sp = get(pos);
        sp.muted = !sp.muted;
        if (sp.player != null) {
            sp.player.applyVolumeFromOptions(sp.muted ? 0f : sp.volumeScale);
        }
        return sp.muted;
    }

    /** 全部屏幕播放状态（兜底清理用） */
    public static Map<BlockPos, ScreenPlayer> getAll() {
        return players;
    }

    public static void setPlayer(BlockPos pos, WaterMediaPlayer player) {
        get(pos).player = player;
    }

    public static void remove(BlockPos pos) {
        ScreenPlayer sp = players.remove(pos);
        if (sp != null && sp.player != null) {
            sp.player.stop();
            sp.player = null;
        }
    }

    /** 停止所有屏幕的播放并清空注册表（断线/离开世界时调用） */
    public static void stopAll() {
        for (ScreenPlayer sp : players.values()) {
            if (sp.player != null) {
                sp.player.stop();
                sp.player = null;
            }
        }
        players.clear();
    }
}
