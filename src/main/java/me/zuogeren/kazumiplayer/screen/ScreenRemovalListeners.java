package me.zuogeren.kazumiplayer.screen;

import net.minecraft.core.BlockPos;
import java.util.UUID;

/**
 * 屏幕方块被移除时的服务端回调（common）。
 * 服务端模块（KazumiPlayerServer）注册实现，用于清理同步组，
 * 避免 common 的方块实体类直接依赖服务端类。
 */
public final class ScreenRemovalListeners {

    public interface Listener {
        /** @param screenPos 被移除屏幕的位置 */
        void onScreenRemoved(UUID screenId, BlockPos screenPos);
    }

    private static volatile Listener listener;

    private ScreenRemovalListeners() {}

    public static void set(Listener l) {
        listener = l;
    }

    public static void dispatch(UUID screenId, BlockPos screenPos) {
        Listener l = listener;
        if (l != null) {
            l.onScreenRemoved(screenId, screenPos);
        }
    }
}
