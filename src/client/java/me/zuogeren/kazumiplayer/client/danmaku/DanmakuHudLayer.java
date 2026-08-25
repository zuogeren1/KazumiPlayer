package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全屏观影弹幕层（挂在 ClientFullscreenState.onHudLayer 画面 blit 之后、控制条之前）。
 *
 * <p>防双消费定稿约定：全屏激活期间由本层独占 {@link ClientDanmakuStore#pollDue} 出队
 * （世界层在 ClientFullscreenState.isActive() 时停用），两条全屏进入路径
 * （GUI 全屏按钮 / RemoteViewerItem 观影器）均经同一 onHudLayer 无差别绘制。
 * 行程与车道语义同世界层，但坐标为屏幕像素域、字号固定不随画面缩放。
 */
public final class DanmakuHudLayer {

    private static final int LANE_COUNT = 5;
    private static final float LANE_H_PX = 12.6f;
    private static final float TEXT_BASELINE_OFFSET_PX = 9.0f;

    private record Active(DanmakuEntry entry, Component text, long startMono, int lane) {}

    private static final Map<BlockPos, List<Active>> ACTIVE = new ConcurrentHashMap<>();

    private DanmakuHudLayer() {}

    /**
     * 全屏帧入口：pictureRect 为等比视频画面矩形（弹幕只在画面范围内滚动）。
     */
    public static void draw(GuiGraphicsExtractor g, Minecraft mc, BlockPos screenPos,
                            int px, int py, int pw, int ph) {
        var config = ClientConfig.CONFIG;
        if (!config.danmakuEnabled.get() || !config.danmakuShowInFullscreen.get()) return;

        long now = MonoClock.millis();
        long travelMs = instantTravelMs();

        // 全屏期独占出队（世界层已停用）
        List<DanmakuEntry> due = ClientDanmakuStore.pollDue(screenPos, 0);
        List<Active> actives = ACTIVE.computeIfAbsent(screenPos, k -> new ArrayList<>());

        int maxOnScreen = config.danmakuMaxOnScreen.get();
        for (DanmakuEntry entry : due) {
            if (actives.size() >= maxOnScreen) {
                me.zuogeren.kazumiplayer.util.KazumiLog.danmaku.debug(
                    "Danmaku dropped at {}: on-screen cap {} reached", screenPos, maxOnScreen);
                break;
            }
            actives.add(new Active(entry, Component.literal(entry.text()), now, nextLane()));
        }
        if (!due.isEmpty()) {
            me.zuogeren.kazumiplayer.util.KazumiLog.danmaku.debug(
                "HUD layer consumed {} due at {} (active {})", due.size(), screenPos, actives.size());
        }
        if (actives.isEmpty()) return;

        Font font = mc.font;
        int alpha = (int) (config.danmakuOpacity.get().floatValue() * 255.0f) << 24 | 0xFFFFFF;

        Iterator<Active> it = actives.iterator();
        while (it.hasNext()) {
            Active active = it.next();
            float progress = (now - active.startMono()) / (float) travelMs;
            if (progress >= 1.0f) {
                it.remove();
                continue;
            }
            float textW = font.width(active.text());
            int headX = px + pw - (int) (progress * (pw + textW));
            int yTop = py + active.lane() * (int) LANE_H_PX + 1;
            g.text(font, active.text().getVisualOrderText(), headX, yTop + (int) TEXT_BASELINE_OFFSET_PX, alpha);
        }
        if (actives.isEmpty()) {
            ACTIVE.remove(screenPos);
        }
    }

    /** 断线/卸载世界时清空活动列表（退出全屏后 draw 不再被调，滞留条目靠此钩子回收） */
    public static void reset() {
        ACTIVE.clear();
    }

    /** 车道轮转游标：连续发言严格分散到不同车道（与世界层同策略） */
    private static int laneCursor;

    private static int nextLane() {
        return Math.floorMod(laneCursor++, LANE_COUNT);
    }

    private static long instantTravelMs() {
        double speed = ClientConfig.CONFIG.danmakuSpeedMultiplier.get();
        return (long) (ClientDanmakuStore.INSTANT_DISPLAY_MS / speed);
    }
}
