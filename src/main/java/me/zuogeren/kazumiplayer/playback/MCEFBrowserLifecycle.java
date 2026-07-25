package me.zuogeren.kazumiplayer.playback;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Phase 0 minimal MCEF browser lifecycle manager.
 * Currently a stub; in later phases this will manage
 * actual MCEFBrowser instances via MCEF.createBrowser().
 */
public class MCEFBrowserLifecycle {
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean initialized = false;

    public void initialize() {
        LOGGER.info("MCEFBrowserLifecycle: Phase 0 stub initialized");
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
