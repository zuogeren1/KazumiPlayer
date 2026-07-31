package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.util.KazumiLog;


/**
 * Phase 0 minimal MCEF browser lifecycle manager.
 * Currently a stub; in later phases this will manage
 * actual MCEFBrowser instances via MCEF.createBrowser().
 */
public class MCEFBrowserLifecycle {

    private boolean initialized = false;

    public void initialize() {
        KazumiLog.playback.info("MCEFBrowserLifecycle: Phase 0 stub initialized");
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
