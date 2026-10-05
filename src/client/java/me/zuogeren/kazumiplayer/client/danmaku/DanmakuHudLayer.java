package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全屏观影弹幕层（挂在 ClientFullscreenState.onHudLayer 画面 blit 之后、控制条之前）。
 *
 * <p>防双消费定稿约定：全屏激活期间由本层独占 {@link ClientDanmakuStore#pollDue} 出队
 * （世界层在 ClientFullscreenState.isActive() 时停用），两条全屏进入路径
 * （GUI 全屏按钮 / RemoteViewerItem 观影器）均经同一 onHudLayer 无差别绘制。
 * 出队时刻用调用方传入的真实播放位置（暂停期为播放器冻结位置），
 * 片内时间轴项才可能到期——早期实现固定传 0 会让片内弹幕在全屏永不出现。
 *
 * <p>三模式布局：SCROLL 右进左出并按车道轮转分配，TOP/BOTTOM 各占独立槽位居中驻留
 * {@link #PIN_HOLD_MS}（不受 speedMultiplier 影响）。坐标为屏幕像素域，字号由
 * danmakuFontScale × 条目 fontSizePercent/100 给整个图层加成（pose 缩放，画面等比时观感与世界层一致）。
 *
 * <p>来源差异：房间互发（{@link DanmakuSource#ROOM_CHAT}）按「昵称：内容」组装、染
 * {@link #ROOM_CHAT_COLOR} 金色并外描矩形文字框；B 站弹幕按包内自身颜色渲染、不画框。
 * 文字框与描边的 alpha 一律由 danmakuOpacity 控制。
 *
 * <p>描边：GuiGraphicsExtractor 的文本 API 无 outlineColor 形参（26.1.2 mojmap
 * GuiGraphicsExtractor L241-L280 五个重载均只到 color/dropShadow），故按原版
 * Font.drawInBatch8xOutline 的同款做法自绘：八向偏移各画一遍描边色，再画主色，主色不叠投影；
 * 文字框走同类的 outline(x, y, w, h, color)（L208-L213，四边 fill 实现）。
 *
 * <p>场景态清理（f10 裁定 A 的渲染侧责任）：本层自记该屏上一帧视频时间，|Δ| 超过
 * {@link ClientDanmakuStore#SEEK_DETECT_MS} 时清空该屏 ACTIVE，避免 seek 后旧弹幕继续飘完行程。
 */
public final class DanmakuHudLayer {

    /** 滚动车道数 */
    private static final int LANE_COUNT = 5;
    /** 固定模式（顶部/底部）各自可用的居中槽位上限 */
    private static final int PIN_SLOT_COUNT = 3;
    private static final int LANE_H_PX = 12;
    private static final int TEXT_BASELINE_OFFSET_PX = 9;
    /** 顶部/底部项驻留时长（毫秒）：固定模式不受 speedMultiplier 影响 */
    private static final long PIN_HOLD_MS = 4500L;
    /** 描边偏移像素：八向各一遍，等效原版 8xOutline 的一像素描边 */
    private static final int[][] OUTLINE_OFFSETS = {
        {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 房间互发弹幕文字框内边距（像素，基础绘制空间） */
    private static final int FRAME_PAD_X = 2;
    private static final int FRAME_PAD_TOP = 3;
    private static final int FRAME_PAD_BOTTOM = 2;

    private record Active(DanmakuEntry entry, FormattedCharSequence text, long startMono,
                          long seq, DanmakuMode mode, int travelMs, int lane) {}

    private static final Map<BlockPos, List<Active>> ACTIVE = new ConcurrentHashMap<>();
    /** 该屏上一帧用于到期判定的视频时间（场景态清理基线） */
    private static final Map<BlockPos, Long> LAST_VIDEO_TIME = new ConcurrentHashMap<>();
    private static long seqCursor;
    /** 滚动车道轮转游标：连续发言严格分散到不同车道 */
    private static int laneCursor;
    private static boolean clearHookInstalled;

    private DanmakuHudLayer() {}

    /**
     * 全屏帧入口：pictureRect 为等比视频画面矩形（弹幕只在画面范围内滚动/驻留）。
     *
     * @param currentVideoTimeMs 该屏当前播放位置（暂停期为冻结位置）；片内时间轴项按它判到期
     */
    public static void draw(GuiGraphicsExtractor g, Minecraft mc, BlockPos screenPos,
                            int px, int py, int pw, int ph, long currentVideoTimeMs) {
        var config = ClientConfig.CONFIG;
        if (!config.danmakuEnabled.get() || !config.danmakuShowInFullscreen.get()) return;
        installClearHook();

        long now = MonoClock.millis();
        long travelMs = scrollTravelMs();

        // 场景态清理先于出队：跳变帧的旧弹幕整批作废
        if (clearOnSeek(screenPos, currentVideoTimeMs)) {
            KazumiLog.danmaku.debug("HUD layer cleared active danmaku at {} after seek to {}ms",
                screenPos, currentVideoTimeMs);
        }

        // 全屏期独占出队（世界层已停用），时刻用真实播放位置
        List<DanmakuEntry> due = ClientDanmakuStore.pollDue(screenPos, currentVideoTimeMs);
        List<Active> actives = ACTIVE.computeIfAbsent(screenPos, k -> new ArrayList<>());

        int maxOnScreen = config.danmakuMaxOnScreen.get();
        int accepted = 0;
        for (DanmakuEntry entry : due) {
            if (!isVisible(entry, config)) continue;
            if (actives.size() >= maxOnScreen) {
                KazumiLog.danmaku.debug("HUD danmaku dropped at {}: on-screen cap {} reached (due {})",
                    screenPos, maxOnScreen, due.size());
                break;
            }
            Active active = admit(entry, actives, now, travelMs);
            if (active == null) continue;
            actives.add(active);
            accepted++;
        }
        if (!due.isEmpty()) {
            KazumiLog.danmaku.debug("HUD layer consumed {} due at {} (admitted {}, active {})",
                due.size(), screenPos, accepted, actives.size());
        }

        for (int i = actives.size() - 1; i >= 0; i--) {
            Active active = actives.get(i);
            if (now - active.startMono() >= lifetimeMs(active)) {
                actives.remove(i);
            }
        }
        if (actives.isEmpty()) {
            ACTIVE.remove(screenPos);
            return;
        }

        Font font = mc.font;
        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int outlineColor = ARGB.black(Math.round(alphaFactor * 255.0f));
        int frameColor = ARGB.color(Math.round(alphaFactor * 255.0f), ROOM_CHAT_COLOR);
        float fontScale = config.danmakuFontScale.get().floatValue();
        int pinRows = Math.max(usedSlotCount(actives, DanmakuMode.TOP),
            usedSlotCount(actives, DanmakuMode.BOTTOM));
        // 固定槽位把滚动区整体下压，避免顶部/底部弹幕与滚动车道重叠（车道几何恒按 100% 基准）
        float rowShift = pinRows * LANE_H_PX;

        for (Active active : actives) {
            int color = entryColor(active.entry(), alphaFactor);
            float textW = font.width(active.text());
            float x;
            float y;
            switch (active.mode()) {
                case TOP -> {
                    x = (pw - textW) / 2.0f;
                    y = active.lane() * LANE_H_PX;
                }
                case BOTTOM -> {
                    x = (pw - textW) / 2.0f;
                    y = ph - (active.lane() + 1) * LANE_H_PX;
                }
                default -> {
                    float progress = (now - active.startMono()) / (float) active.travelMs();
                    // 右进左出：p=0 时文字头贴右缘，p=1 时文字尾贴左缘滑出
                    x = pw - progress * (pw + textW);
                    y = rowShift + active.lane() * LANE_H_PX;
                }
            }
            // 按条目各自缩放：比例自入场固定（只由该条目 fontSizePercent 决定，不随在屏集合变化），
            // 大字号条目在自己的车道内放大并可轻微溢出，但不改变其他条目的位置与大小。
            // 缩放内所有坐标与偏移量都处于该条目的局部像素域，主字/描边/文字框天然同尺度，不再错位。
            float entryScale = fontScale * Math.max(10, active.entry().fontSizePercent())
                / (float) DanmakuEntry.FONT_SIZE_STANDARD;
            g.pose().pushMatrix();
            g.pose().translate(px + x, py + y);
            g.pose().scale(entryScale, entryScale);
            if (active.entry().source() == DanmakuSource.ROOM_CHAT) {
                g.outline(-FRAME_PAD_X, -FRAME_PAD_TOP, Math.round(textW) + 2 * FRAME_PAD_X,
                    LANE_H_PX + FRAME_PAD_TOP + FRAME_PAD_BOTTOM, frameColor);
            }
            if (outline) {
                for (int[] offset : OUTLINE_OFFSETS) {
                    g.text(font, active.text(), offset[0], TEXT_BASELINE_OFFSET_PX + offset[1],
                        outlineColor, false);
                }
                g.text(font, active.text(), 0, TEXT_BASELINE_OFFSET_PX, color, false);
            } else {
                g.text(font, active.text(), 0, TEXT_BASELINE_OFFSET_PX, color, true);
            }
            g.pose().popMatrix();
        }
    }

    /**
     * 单条准入：分配车道/槽位。
     *
     * @return 已登记的 Active；无空位返回 null
     */
    private static Active admit(DanmakuEntry entry, List<Active> actives, long now, long travelMs) {
        long seq = seqCursor++;
        return switch (entry.mode()) {
            case TOP -> {
                int slot = firstFreePin(actives, DanmakuMode.TOP);
                yield slot < 0 ? null : new Active(entry, entryText(entry), now, seq,
                    DanmakuMode.TOP, 0, slot);
            }
            case BOTTOM -> {
                int slot = firstFreePin(actives, DanmakuMode.BOTTOM);
                yield slot < 0 ? null : new Active(entry, entryText(entry), now, seq,
                    DanmakuMode.BOTTOM, 0, slot);
            }
            case SCROLL -> {
                int lane = pickLane(actives);
                yield lane < 0 ? null : new Active(entry, entryText(entry), now, seq,
                    DanmakuMode.SCROLL, (int) travelMs, lane);
            }
        };
    }

    /**
     * 车道选择：从轮转游标起取第一条空闲车道；全占用时退让给「最早登记的在途弹幕」所在车道
     * （拥塞优先保上屏，上屏条数由 danmakuMaxOnScreen 单点约束）。
     */
    private static int pickLane(List<Active> actives) {
        boolean[] occupied = new boolean[LANE_COUNT];
        long[] oldestSeq = new long[LANE_COUNT];
        for (int lane = 0; lane < LANE_COUNT; lane++) {
            oldestSeq[lane] = Long.MAX_VALUE;
        }
        for (Active active : actives) {
            if (active.mode() != DanmakuMode.SCROLL) continue;
            occupied[active.lane()] = true;
            oldestSeq[active.lane()] = Math.min(oldestSeq[active.lane()], active.seq());
        }
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int lane = Math.floorMod(laneCursor + offset, LANE_COUNT);
            if (!occupied[lane]) {
                laneCursor = Math.floorMod(lane + 1, LANE_COUNT);
                return lane;
            }
        }
        int bestLane = 0;
        for (int lane = 1; lane < LANE_COUNT; lane++) {
            if (oldestSeq[lane] < oldestSeq[bestLane]) {
                bestLane = lane;
            }
        }
        laneCursor = Math.floorMod(bestLane + 1, LANE_COUNT);
        return bestLane;
    }

    private static int firstFreePin(List<Active> actives, DanmakuMode mode) {
        boolean[] used = new boolean[PIN_SLOT_COUNT];
        for (Active active : actives) {
            if (active.mode() == mode && active.lane() < PIN_SLOT_COUNT) {
                used[active.lane()] = true;
            }
        }
        for (int i = 0; i < PIN_SLOT_COUNT; i++) {
            if (!used[i]) return i;
        }
        return -1;
    }

    private static int usedSlotCount(List<Active> actives, DanmakuMode mode) {
        boolean[] used = new boolean[PIN_SLOT_COUNT];
        for (Active active : actives) {
            if (active.mode() == mode && active.lane() < PIN_SLOT_COUNT) {
                used[active.lane()] = true;
            }
        }
        int count = 0;
        for (boolean flag : used) {
            if (flag) count++;
        }
        return count;
    }

    /** 条目寿命：滚动项按行程，固定项按驻留时长（固定模式不受 speedMultiplier 影响） */
    private static long lifetimeMs(Active active) {
        return active.mode() == DanmakuMode.SCROLL ? active.travelMs() : PIN_HOLD_MS;
    }

    /** 文本：房间互发按「昵称：内容」组装（senderName 为 null 的 B 站来源不加前缀） */
    private static FormattedCharSequence entryText(DanmakuEntry entry) {
        if (entry.source() == DanmakuSource.ROOM_CHAT
                && entry.senderName() != null && !entry.senderName().isEmpty()) {
            return Component.literal(entry.senderName() + "：" + entry.text()).getVisualOrderText();
        }
        return Component.literal(entry.text()).getVisualOrderText();
    }

    /** 条目定色：房间互发恒金色，其余用包内颜色；alpha 一律由 danmakuOpacity 决定 */
    private static int entryColor(DanmakuEntry entry, float alphaFactor) {
        int rgb = entry.source() == DanmakuSource.ROOM_CHAT ? ROOM_CHAT_COLOR : entry.colorRgb();
        int alpha = Math.max(0, Math.min(255, Math.round(alphaFactor * 255.0f)));
        return ARGB.color(alpha, rgb & 0xFFFFFF);
    }

    /** 来源与模式开关过滤：关闭的来源/模式在出队后即丢弃，不占容量闸 */
    private static boolean isVisible(DanmakuEntry entry, ClientConfig config) {
        boolean sourceOn = switch (entry.source()) {
            case BILIBILI_VIDEO -> config.danmakuBilibiliVideo.get();
            case BILIBILI_LIVE -> config.danmakuBilibiliLive.get();
            case ROOM_CHAT -> config.danmakuRoomChat.get();
        };
        boolean modeOn = switch (entry.mode()) {
            case SCROLL -> config.danmakuShowScroll.get();
            case TOP -> config.danmakuShowTop.get();
            case BOTTOM -> config.danmakuShowBottom.get();
        };
        return sourceOn && modeOn;
    }

    /** 断线/卸载世界时清空活动列表（退出全屏后 draw 不再被调，滞留条目靠此钩子回收） */
    public static void reset() {
        ACTIVE.clear();
        LAST_VIDEO_TIME.clear();
    }

    /** 跳变帧清理：|Δ| 超过 Store 的 seek 阈值即清空该屏活动弹幕（首帧只记基线） */
    public static boolean clearOnSeek(BlockPos screenPos, long currentVideoTimeMs) {
        Long previous = LAST_VIDEO_TIME.put(screenPos, currentVideoTimeMs);
        if (previous == null) return false;
        if (Math.abs(currentVideoTimeMs - previous) <= ClientDanmakuStore.SEEK_DETECT_MS) return false;
        return ACTIVE.remove(screenPos) != null;
    }

    /** 安装 Store 清屏回调（懒注册一次）：换集/停止时立即释放该屏在途弹幕 */
    private static void installClearHook() {
        if (clearHookInstalled) return;
        clearHookInstalled = true;
        ClientDanmakuStore.addClearListener(pos -> {
            ACTIVE.remove(pos);
            LAST_VIDEO_TIME.remove(pos);
        });
    }

    /** 滚动行程：基准 5s 除以速度倍率（所有滚动项同一速度，与弹幕数量无关） */
    private static long scrollTravelMs() {
        double speed = ClientConfig.CONFIG.danmakuSpeedMultiplier.get();
        return (long) (ClientDanmakuStore.INSTANT_DISPLAY_MS / speed);
    }
}
