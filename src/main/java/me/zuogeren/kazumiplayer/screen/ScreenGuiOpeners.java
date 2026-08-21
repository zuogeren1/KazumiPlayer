package me.zuogeren.kazumiplayer.screen;

import net.minecraft.core.BlockPos;

/**
 * 屏幕方块右键时打开客户端 URL 输入界面的回调（common）。
 * 客户端模块（KazumiPlayerClient）注册实现，避免 common 的方块类直接依赖客户端类。
 */
public final class ScreenGuiOpeners {

    public interface Opener {
        /** @param screenPos 被右键的屏幕方块位置 */
        void openUrlInput(BlockPos screenPos);
    }

    private static volatile Opener opener;

    private ScreenGuiOpeners() {}

    public static void set(Opener o) {
        opener = o;
    }

    public static void openUrlInput(BlockPos screenPos) {
        Opener o = opener;
        if (o != null) {
            o.openUrlInput(screenPos);
        }
    }
}
