package me.zuogeren.kazumiplayer.client;

/**
 * 客户端屏幕播放状态变更监听器。
 * 音响 BE 注册此监听器以跟随屏幕播放器的暂停/seek/停止。
 */
public interface PlayStateListener {
    void onPause();
    void onResume();
    void onSeek(long positionMs);
    void onStop();
}
