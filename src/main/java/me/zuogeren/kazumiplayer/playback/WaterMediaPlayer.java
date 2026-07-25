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
        Minecraft mc = Minecraft.getInstance();
        // 在渲染线程外启动 MRL 异步加载和重试
        new Thread(() -> {
            for (int retry = 0; retry < 10; retry++) {
                MRL mrl = MediaAPI.mrl(videoUrl);
                if (mrl.source(0) != null) {
                    mc.execute(() -> createAndStart(mrl, mc));
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            LOGGER.error("MRL loading timeout: {}", videoUrl);
        }, "KazumiPlayer-MRL-Loader").start();
    }

    private void createAndStart(MRL mrl, Minecraft mc) {
        try {
            player = MediaAPI.createPlayer(mrl,
                () -> MediaAPI.glEngine(Thread.currentThread(), mc),
                () -> MediaAPI.jsEngine());
            if (player == null) {
                LOGGER.error("Failed to create player for: {}", mrl.uri);
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
