package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音响客户端音频管理（客户端专属）。
 * 原 SpeakerBlockEntity 中的客户端逻辑迁移至此，按音响位置独立维护。
 *
 * 注：音频发声仍为占位（等待 WaterMedia v3 支持 audio/video 独立开关），
 * 此处保留漂移校正等基础结构。
 */
public class SpeakerClientAudio implements PlayStateListener {

    private static final Map<BlockPos, SpeakerClientAudio> audios = new ConcurrentHashMap<>();

    private WaterMediaPlayer audioPlayer;
    private int driftTickCounter;
    private static final int DRIFT_CHECK_INTERVAL = 100; // 5 秒 @20tps
    private static final long DRIFT_THRESHOLD_MS = 500;

    public static void tick(SpeakerBlockEntity speaker) {
        if (speaker.getLevel() == null || !speaker.getLevel().isClientSide()) return;
        if (!speaker.isLinked()) return;
        audios.computeIfAbsent(speaker.getBlockPos(), k -> new SpeakerClientAudio())
            .clientTick(speaker);
    }

    private void clientTick(SpeakerBlockEntity speaker) {
        // 检查屏幕 BE 是否存在（双向判断：存在→取消静音，不存在→静音）
        VideoScreenBlockEntity screen = speaker.getLinkedScreen();
        if (audioPlayer != null) {
            audioPlayer.getPlayer().mute(screen == null);
        }

        // 漂移校正
        if (audioPlayer == null || screen == null) return;
        WaterMediaPlayer screenPlayer = ScreenPlayerManager.getPlayer(screen.getBlockPos());
        if (screenPlayer == null) return;
        if (!audioPlayer.isPlaying()) return;

        driftTickCounter++;
        if (driftTickCounter >= DRIFT_CHECK_INTERVAL) {
            driftTickCounter = 0;
            // 暂停时不校正（两者都不动）
            if (screen.isPlaybackPaused()) return;
            long screenTime = screenPlayer.getTimeMs();
            long speakerTime = audioPlayer.getTimeMs();
            if (Math.abs(screenTime - speakerTime) > DRIFT_THRESHOLD_MS) {
                audioPlayer.seek(screenTime);
                KazumiLog.speaker.debug("Speaker drift corrected: {}ms → {}ms", speakerTime, screenTime);
            }
        }
    }

    // ---- PlayStateListener（跟随屏幕播放器） ----

    @Override
    public void onPause() {
        if (audioPlayer != null) audioPlayer.pause();
    }

    @Override
    public void onResume() {
        if (audioPlayer != null) {
            audioPlayer.resume();
            driftTickCounter = 0; // 恢复后重置漂移计数器
        }
    }

    @Override
    public void onSeek(long positionMs) {
        if (audioPlayer != null) audioPlayer.seek(positionMs);
    }

    @Override
    public void onStop() {
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
    }
}
