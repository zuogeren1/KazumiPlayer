package me.zuogeren.kazumiplayer.playback;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;

/**
 * WaterMedia V3 播放器封装 (FFmpeg)
 */
public class WaterMediaPlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private MediaPlayer player;

    public void play(String videoUrl) {
        try {
            MRL mrl = MediaAPI.mrl(videoUrl);
            Minecraft mc = Minecraft.getInstance();
            player = MediaAPI.createPlayer(mrl,
                () -> MediaAPI.glEngine(Thread.currentThread(), mc),
                null);  // 暂不处理音频
            if (player == null) {
                LOGGER.error("Failed to create player for: {}", videoUrl);
                return;
            }
            player.start();
        } catch (Exception e) {
            LOGGER.error("Playback failed: {}", e.getMessage());
        }
    }

    public boolean isPlaying() {
        return player != null && player.playing();
    }

    public long getTextureId() {
        return player != null ? player.texture() : 0;
    }

    public long getTimeMs() {
        return player != null ? player.time() : 0;
    }

    public long getDurationMs() {
        return player != null ? player.duration() : 0;
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void resume() {
        if (player != null) player.resume();
    }

    public void seek(long ms) {
        if (player != null) player.seek(ms);
    }

    public void stop() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    public MediaPlayer getPlayer() { return player; }
}
