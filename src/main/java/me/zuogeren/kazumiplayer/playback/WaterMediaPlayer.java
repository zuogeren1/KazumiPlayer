package me.zuogeren.kazumiplayer.playback;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Phase 0 minimal wrapper around WaterMedia's VideoLanPlayer.
 * Currently a stub; in later phases this will wrap
 * org.watermedia.api.player.videolan.VideoPlayer for VLC playback.
 */
public class WaterMediaPlayer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean initialized = false;

    public void initialize() {
        LOGGER.info("WaterMediaPlayer: Phase 0 stub initialized");
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void release() {
        LOGGER.info("WaterMediaPlayer: released");
        initialized = false;
    }
}
