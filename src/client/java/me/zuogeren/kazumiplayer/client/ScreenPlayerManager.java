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

    /** 单个屏幕的客户端播放状态 */
    public static class ScreenPlayer {
        public WaterMediaPlayer player;
        public String lastEpisodeUrl = "";
        public long playbackStartedAt; // 防抖：上次启动播放的时间戳
        public boolean endedNotified;
        /** 本次启动后是否进入过播放态——解析失败/租约放弃的播放器永远为 false，供僵尸自愈判定 */
        public boolean everPlayed;
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
