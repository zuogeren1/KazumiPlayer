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
        public long lastAppliedPosition = -1;
        public long playbackStartedAt; // 防抖：上次启动播放的时间戳
        public boolean endedNotified;
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

    public static void setPlayer(BlockPos pos, WaterMediaPlayer player) {
        get(pos).player = player;
    }

    public static void remove(BlockPos pos) {
        players.remove(pos);
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
