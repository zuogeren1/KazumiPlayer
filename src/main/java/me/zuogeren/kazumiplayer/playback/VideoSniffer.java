package me.zuogeren.kazumiplayer.playback;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Phase 0 minimal video URL sniffer using MCEF.
 * Currently a stub; in later phases this will load
 * episode pages in MCEF browser and inject JS to sniff video URLs.
 */
public class VideoSniffer {
    private static final Logger LOGGER = LogUtils.getLogger();

    public VideoSniffer() {
        LOGGER.info("VideoSniffer: Phase 0 stub created");
    }
}
