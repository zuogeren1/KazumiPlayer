package me.zuogeren.kazumiplayer.screen;

import net.minecraft.world.level.block.state.BlockState;

public enum VideoState {
    IDLE,
    LOADING,
    PLAYING,
    ERROR,
    STOPPED;

    public static VideoState fromName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return IDLE;
        }
    }
}
