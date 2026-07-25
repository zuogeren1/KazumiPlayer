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
    private long pendingSeekMs = -1;
    private boolean pendingPause;

    public void play(String videoUrl) {
        Minecraft mc = Minecraft.getInstance();
        new Thread(() -> {
            for (int retry = 0; retry < 30; retry++) {
                MRL mrl = MediaAPI.mrl(videoUrl);
                if (mrl.source(0) != null) {
                    mc.execute(() -> createAndStart(mrl, mc));
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            LOGGER.error("MRL loading timeout: {}", videoUrl);
            mc.execute(() -> mc.gui.getChat().addClientSystemMessage(
                net.minecraft.network.chat.Component.literal("§c视频加载超时，请检查网络或稍后重试")));
        }, "KazumiPlayer-MRL-Loader").start();
    }

    private void createAndStart(MRL mrl, Minecraft mc) {
        try {
            player = MediaAPI.createPlayer(mrl,
                () -> MediaAPI.glEngine(Thread.currentThread(), mc),
                () -> MediaAPI.jsEngine());
            if (player == null) {
                LOGGER.error("Failed to create player for: {}", mrl.uri);
                mc.execute(() -> mc.gui.getChat().addClientSystemMessage(
                    net.minecraft.network.chat.Component.literal("§c创建播放器失败")));
                return;
            }
            player.start();
            // seek 交给外部 tick 延迟执行（此时 demuxer 尚未就绪）
        } catch (Exception e) {
            LOGGER.error("Playback failed: {}", e.getMessage());
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

    public long getTimeMs() {
        return player != null ? player.time() : 0;
    }

    public long getDurationMs() {
        return player != null ? player.duration() : 0;
    }

    public void pause() {
        if (player != null) player.pause();
        else pendingPause = true;
    }

    public void resume() {
        if (player != null) player.resume();
        else pendingPause = false;
    }

    public void seek(long ms) {
        if (player != null && player.playing()) player.seek(ms);
        else pendingSeekMs = ms;
    }

    public void stop() {
        pendingSeekMs = -1;
        pendingPause = false;
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    public boolean hasPendingSeek() {
        return pendingSeekMs >= 0;
    }

    public void applyPendingSeek() {
        if (player != null && player.playing() && pendingSeekMs >= 0) {
            player.seek(pendingSeekMs);
            LOGGER.info("Delayed seek: {}ms (time={})", pendingSeekMs, player.time());
            pendingSeekMs = -1;
        }
    }

    public MediaPlayer getPlayer() { return player; }
}
