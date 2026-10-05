package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
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
 * 片内时间轴项才可能到期。
 *
 * <p>布局按带组织，三条带几何互不影响：滚动带（SCROLL 右进左出 / REVERSE 左进右出，互为镜像）
 * 起点恒为显示区顶部并按车道轮转；TOP 带自顶部向下、BOTTOM 带自底部向上，各三槽居中驻留
 * {@link #PIN_HOLD_MS}（不受 speedMultiplier 影响）。带间重叠时固定项压在滚动项之上（B 站语义）。
 *
 * <p>车道准入是占用感知的：同车道每条在途弹幕的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) +
 * {@link #MIN_GAP_PX} 才允许新条入轨，没有满足条件的车道即丢弃该条并计入 DEBUG 统计，不再硬塞同一行；
 * 占位宽度取两者的较大值，保证更宽的追随弹幕（行程更快）在最坏时刻也不会追上前车。
 * 单条缩放的实际上限由车道高度决定，大字号条目不溢出到相邻车道。
 *
 * <p>社交优先：出队后按来源优先级（ROOM_CHAT &gt; BILIBILI_LIVE &gt; BILIBILI_VIDEO）稳定排序再准入；
 * danmakuMaxOnScreen 容量闸只拦视频片内条目，社交条目不受上限约束（仍计入在屏数、仍受车道可用性约束）；
 * 社交条目找不到空闲车道时抢占——丢弃该车道里最接近离场的视频片内条目腾位（无视频占位者则照常丢弃），
 * 抢占计入 evicted-for-social DEBUG 计数。
 *
 * <p>坐标域：车道/槽位/滚动位移都在画面矩形 (px, py, pw, ph) 的像素域内，字号由
 * danmakuFontScale × 条目 fontSizePercent/100 作为逐条 pose 缩放施加（比例自入场固定，
 * 不受在屏集合影响），几何量按该缩放折算成实际像素后参与定位与占位计算。
 *
 * <p>来源差异：房间互发（{@link DanmakuSource#ROOM_CHAT}）按「昵称：内容」组装、染
 * {@link #ROOM_CHAT_COLOR} 金色并外描一个空心矩形文字框；B 站弹幕按包内自身颜色渲染、不画框。
 * 文字框矩形由字形实际绘制外接框（{@code Font#prepareText} 的 bounds）四边各外扩 {@link #FRAME_PAD}
 * 像素推导，文字在框内水平与垂直都居中。文字框与描边的 alpha 一律由 danmakuOpacity 控制。
 *
 * <p>描边：GuiGraphicsExtractor 的文本 API 无 outlineColor 形参（26.1.2 mojmap
 * GuiGraphicsExtractor L241-L280 五个重载均只到 color/dropShadow），故按原版
 * Font.drawInBatch8xOutline 的同款做法自绘：八向偏移各画一遍描边色，再画主色，主色不叠投影。
 * 文字框在画面像素域自绘四条 {@link #FRAME_EDGE} 细边（{@code fill}），框内不填充；
 * 整批框先于整批文字提交，GUI 状态同一层内矩形先于字形绘制，故框恒在文字下层。
 *
 * <p>场景态清理（f10 裁定 A 的渲染侧责任）：本层自记该屏上一帧视频时间，|Δ| 超过
 * {@link ClientDanmakuStore#SEEK_DETECT_MS} 时清空该屏 ACTIVE，避免 seek 后旧弹幕继续飘完行程。
 */
public final class DanmakuHudLayer {

    /** 滚动车道数 */
    private static final int LANE_COUNT = 5;
    /** 固定模式（顶部/底部）各自可用的居中槽位上限 */
    private static final int PIN_SLOT_COUNT = 3;
    /** 车道行高（像素，恒按 100% 字号基准） */
    private static final int LANE_H_PX = 12;
    /** 原版字体字形行高（像素） */
    private static final int GLYPH_HEIGHT_PX = 9;
    /** 文字左上角相对车道顶部的偏移像素 */
    private static final int TEXT_BASELINE_OFFSET_PX = 9;
    /** 顶部/底部项驻留时长（毫秒）：固定模式不受 speedMultiplier 影响 */
    private static final long PIN_HOLD_MS = 4500L;
    /** 车道让位判据的水平间隔（像素）：旧条已推进像素须 ≥ max(新条,旧条)文本宽 + 此值才释放车道 */
    private static final float MIN_GAP_PX = 6.0f;
    /** 描边偏移像素：八向各一遍，等效原版 8xOutline 的一像素描边 */
    private static final int[][] OUTLINE_OFFSETS = {
        {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 房间互发弹幕文字框内边距（画面像素，四边相等）与线宽（画面像素） */
    private static final int FRAME_PAD = 2;
    private static final int FRAME_EDGE = 1;

    /**
     * 在屏条目。
     *
     * @param textWPx 条目文本的实际像素宽（基础字宽 × {@code scale}）
     * @param scale   条目 pose 缩放：danmakuFontScale × fontSizePercent/100，上限为单个车道高度
     */
    private record Active(DanmakuEntry entry, FormattedCharSequence text, long startMono,
                          DanmakuMode mode, int travelMs, int lane,
                          float textWPx, float scale) {}

    /**
     * 车道分配结果。
     *
     * @param lane       可用车道下标（&lt; 0 表示无车道可入，调用侧丢弃该条）
     * @param evictIndex 需先移除的在途条目下标（-1 表示无需腾位）
     */
    private record LanePick(int lane, int evictIndex) {}

    /** 来源优先级（数值越小越优先）：社交弹幕先占位，视频片内条目最低 */
    private static int sourcePriority(DanmakuSource source) {
        return switch (source) {
            case ROOM_CHAT -> 0;
            case BILIBILI_LIVE -> 1;
            case BILIBILI_VIDEO -> 2;
        };
    }

    /** 视频片内条目：受 danmakuMaxOnScreen 容量闸限制，且可被更高优先级的弹幕抢占腾位 */
    private static boolean isVideoDanmaku(DanmakuSource source) {
        return source == DanmakuSource.BILIBILI_VIDEO;
    }

    private static final Map<BlockPos, List<Active>> ACTIVE = new ConcurrentHashMap<>();
    /** 该屏上一帧用于到期判定的视频时间（场景态清理基线） */
    private static final Map<BlockPos, Long> LAST_VIDEO_TIME = new ConcurrentHashMap<>();
    /** 滚动车道轮转游标：多条同时可入时用于分散到不同车道 */
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
        // 社交优先占位：同一帧内按来源优先级稳定排序后再准入（同优先级保持出队序）
        if (due.size() > 1) {
            due.sort((a, b) -> Integer.compare(sourcePriority(a.source()), sourcePriority(b.source())));
        }
        List<Active> actives = ACTIVE.computeIfAbsent(screenPos, k -> new ArrayList<>());
        Font font = mc.font;
        float fontScale = config.danmakuFontScale.get().floatValue();

        int maxOnScreen = config.danmakuMaxOnScreen.get();
        int accepted = 0;
        int noLane = 0;
        int capped = 0;
        int evicted = 0;
        int skipped = 0;
        for (DanmakuEntry entry : due) {
            if (entry.mode() == DanmakuMode.ADVANCED) {
                skipped++;
                continue;
            }
            if (!isVisible(entry, config)) continue;
            // 容量闸只拦视频片内条目：社交条目不受上限约束（仍计入 actives、仍受车道可用性约束）
            if (isVideoDanmaku(entry.source()) && actives.size() >= maxOnScreen) {
                capped++;
                continue;
            }
            FormattedCharSequence text = entryText(entry);
            float scalePercent = Math.max(10, entry.fontSizePercent())
                / (float) DanmakuEntry.FONT_SIZE_STANDARD;
            // 缩放上限取单条车道高度：大字号条目在自己的车道内放大，不压到相邻车道
            float entryScale = Math.min(fontScale * scalePercent,
                LANE_H_PX / (float) GLYPH_HEIGHT_PX);
            float drawWPx = font.width(text) * entryScale;
            int before = actives.size();
            Active active = admit(entry, text, actives, now, travelMs, drawWPx, entryScale, pw);
            if (active == null) {
                noLane++;
                continue;
            }
            // 抢位：admit 已就地移除被抢占的在途条目，此时净增为 0
            if (actives.size() < before) evicted++;
            actives.add(active);
            accepted++;
        }
        if (!due.isEmpty() || noLane > 0 || capped > 0 || evicted > 0 || skipped > 0) {
            KazumiLog.danmaku.debug(
                "HUD layer consumed {} due at {} (admitted {}, no-free-lane {}, capped-video {}, evicted-for-social {}, advanced-skipped {}, active {})",
                due.size(), screenPos, accepted, noLane, capped, evicted, skipped, actives.size());
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

        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int outlineColor = ARGB.black(Math.round(alphaFactor * 255.0f));
        int frameColor = ARGB.color(Math.round(alphaFactor * 255.0f), ROOM_CHAT_COLOR);

        // 文字框整批先于文字提交：框恒在文字下层（同一层内矩形先于字形绘制）
        for (Active active : actives) {
            if (active.entry().source() != DanmakuSource.ROOM_CHAT) continue;
            drawFrame(g, font, active, now, px, py, pw, ph, !outline, frameColor);
        }

        // 分带提交：滚动带先画，TOP/BOTTOM 后画 → 三条带重叠处固定项压在上层
        drawBand(g, font, actives, DanmakuMode.SCROLL, now, px, py, pw, ph, outline, outlineColor, alphaFactor);
        drawBand(g, font, actives, DanmakuMode.REVERSE, now, px, py, pw, ph, outline, outlineColor, alphaFactor);
        drawBand(g, font, actives, DanmakuMode.TOP, now, px, py, pw, ph, outline, outlineColor, alphaFactor);
        drawBand(g, font, actives, DanmakuMode.BOTTOM, now, px, py, pw, ph, outline, outlineColor, alphaFactor);
    }

    /**
     * 单条带的提交：滚动带与逆向滚动带起点恒为显示区顶部（车道几何不受固定项影响），
     * TOP 自顶部向下、BOTTOM 自底部向上。
     */
    private static void drawBand(GuiGraphicsExtractor g, Font font, List<Active> actives, DanmakuMode band,
                                 long now, int px, int py, int pw, int ph, boolean outline,
                                 int outlineColor, float alphaFactor) {
        for (Active active : actives) {
            if (active.mode() != band) continue;
            float x = posX(active, now, pw);
            // 整条移出显示区即不再提交（滚动/逆向滚动出界后无可见部分）
            if (isOutside(x, active.textWPx(), pw)) continue;
            float y = posY(active, ph);
            float entryScale = active.scale();
            // 按条目各自缩放：比例自入场固定，任一条目上下场都不改变他人的位置与大小；
            // 缩放内所有坐标与偏移量都处于该条目的局部像素域，主字/描边/文字框天然同尺度
            g.pose().pushMatrix();
            g.pose().translate(px + x, py + y);
            g.pose().scale(entryScale, entryScale);
            int color = entryColor(active.entry(), alphaFactor);
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
     * 空心文字框：画面像素域四条 {@link #FRAME_EDGE} 细边，框内不填充，随条目车道/位移同步。
     * 矩形由文字实际绘制外接框四边各外扩 {@link #FRAME_PAD} 像素推导（含基线偏移与逐条缩放），
     * 故文字在框内水平与垂直都居中，边框不压在字形上。
     */
    private static void drawFrame(GuiGraphicsExtractor g, Font font, Active active, long now,
                                  int px, int py, int pw, int ph, boolean dropShadow, int color) {
        float x = posX(active, now, pw);
        if (isOutside(x, active.textWPx(), pw)) return;
        ScreenRectangle ink = inkBounds(font, active.text(), TEXT_BASELINE_OFFSET_PX, color, dropShadow);
        if (ink == null) return;
        int[] box = frameBox(ink, active.scale(), x, posY(active, ph), px, py);
        int left = box[0];
        int top = box[1];
        int right = box[2];
        int bottom = box[3];
        g.fill(left, top, right, top + FRAME_EDGE, color);
        g.fill(left, bottom - FRAME_EDGE, right, bottom, color);
        g.fill(left, top + FRAME_EDGE, left + FRAME_EDGE, bottom - FRAME_EDGE, color);
        g.fill(right - FRAME_EDGE, top + FRAME_EDGE, right, bottom - FRAME_EDGE, color);
    }

    /** 文字实际绘制外接框（条目局部域）：取字形四边形真实边界（含投影），空文本返回 null */
    private static ScreenRectangle inkBounds(Font font, FormattedCharSequence text, float localTextTop,
                                            int color, boolean dropShadow) {
        return font.prepareText(text, 0.0f, localTextTop, color, dropShadow, false, 0).bounds();
    }

    /**
     * 文字框矩形（left, top, right, bottom；画面像素域）= 文字实际绘制外接框四边各外扩 {@link #FRAME_PAD} 像素。
     *
     * @param x    条目文字绘制原点 x（画面像素域，未含 px 偏移）
     * @param yTop 条目文字绘制原点 y（画面像素域，未含 py 偏移）
     */
    private static int[] frameBox(ScreenRectangle ink, float q, float x, float yTop, int px, int py) {
        int left = Math.round(px + x + ink.left() * q - FRAME_PAD);
        int top = Math.round(py + yTop + ink.top() * q - FRAME_PAD);
        int right = Math.round(px + x + ink.right() * q + FRAME_PAD);
        int bottom = Math.round(py + yTop + ink.bottom() * q + FRAME_PAD);
        return new int[] {left, top, right, bottom};
    }

    /** 条目左上角在画面内的横向位置（画面像素域）：SCROLL 右进左出、REVERSE 左进右出（镜像） */
    private static float posX(Active active, long now, int pw) {
        float drawWPx = active.textWPx();
        // 固定项居中驻留，与行程无关
        if (active.mode() == DanmakuMode.TOP || active.mode() == DanmakuMode.BOTTOM) {
            return (pw - drawWPx) / 2.0f;
        }
        float progress = (now - active.startMono()) / (float) active.travelMs();
        // REVERSE 为镜像：p=0 时文字整体藏于左缘外，p=1 时文字尾越过右缘
        return active.mode() == DanmakuMode.REVERSE
            ? progress * (pw + drawWPx) - drawWPx
            : pw - progress * (pw + drawWPx);
    }

    /** 条目所在带的纵向位置（画面像素域）：BOTTOM 带自底部往上，其余带自显示区顶部往下 */
    private static float posY(Active active, int ph) {
        return active.mode() == DanmakuMode.BOTTOM
            ? ph - (active.lane() + 1) * LANE_H_PX
            : active.lane() * LANE_H_PX;
    }

    /** 条目整条移出显示区（滚动/逆向滚动的出场态），固定项居中恒在显示区内 */
    private static boolean isOutside(float x, float drawWPx, int pw) {
        return x > pw || x + drawWPx < 0;
    }

    /**
     * 单条准入：占用感知地分配车道/槽位。
     *
     * <p>社交条目抢占腾位时会直接从 actives 就地移除被抢占的在途条目（调用侧据列表长度变化计数）。
     *
     * @return 已登记的 Active；无可用车道/槽位返回 null（调用侧丢弃并计入 DEBUG）
     */
    private static Active admit(DanmakuEntry entry, FormattedCharSequence text, List<Active> actives,
                                long now, long travelMs, float drawWPx, float entryScale, int pw) {
        return switch (entry.mode()) {
            case TOP -> {
                int slot = firstFreePin(actives, DanmakuMode.TOP);
                yield slot < 0 ? null : new Active(entry, text, now,
                    DanmakuMode.TOP, 0, slot, drawWPx, entryScale);
            }
            case BOTTOM -> {
                int slot = firstFreePin(actives, DanmakuMode.BOTTOM);
                yield slot < 0 ? null : new Active(entry, text, now,
                    DanmakuMode.BOTTOM, 0, slot, drawWPx, entryScale);
            }
            case ADVANCED -> null;
            case SCROLL, REVERSE -> {
                LanePick pick = pickLane(actives, entry.mode(), drawWPx, pw, now, travelMs,
                    !isVideoDanmaku(entry.source()));
                if (pick.lane() < 0) yield null;
                if (pick.evictIndex() >= 0) actives.remove(pick.evictIndex());
                yield new Active(entry, text, now,
                    entry.mode(), (int) travelMs, pick.lane(), drawWPx, entryScale);
            }
        };
    }

    /**
     * 车道分配：先按轮转游标找一条已让出空间的车道；全部车道都在占用期且该条可抢占时，再按同一
     * 轮转序试算腾位——移除该车道里行程 progress 最大的视频片内条目后确实让出空间才标记抢占
     * （试算不成立不白丢视频条目）；无可用车道返回 lane &lt; 0，由调用侧丢弃该条。
     *
     * @param preempt 该条是否为可抢占的社交条目（视频片内条目不得抢占）
     */
    private static LanePick pickLane(List<Active> actives, DanmakuMode mode, float newWPx, int pw,
                                     long now, long travelMs, boolean preempt) {
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int lane = Math.floorMod(laneCursor + offset, LANE_COUNT);
            if (laneReleased(actives, mode, lane, newWPx, pw, now, travelMs)) {
                laneCursor = Math.floorMod(lane + 1, LANE_COUNT);
                return new LanePick(lane, -1);
            }
        }
        if (!preempt) return new LanePick(-1, -1);
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int lane = Math.floorMod(laneCursor + offset, LANE_COUNT);
            int victim = evictCandidate(actives, mode, lane, now);
            if (victim < 0) continue;
            if (!laneReleased(actives, mode, lane, newWPx, pw, now, travelMs, victim)) continue;
            laneCursor = Math.floorMod(lane + 1, LANE_COUNT);
            return new LanePick(lane, victim);
        }
        return new LanePick(-1, -1);
    }

    /** 抢占候选：该车道里行程 progress 最大（最接近离场）的视频片内条目下标；没有则 -1 */
    private static int evictCandidate(List<Active> actives, DanmakuMode mode, int lane, long now) {
        int candidate = -1;
        float bestProgress = -1.0f;
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() != mode || active.lane() != lane) continue;
            if (!isVideoDanmaku(active.entry().source())) continue;
            float progress = (now - active.startMono()) / (float) active.travelMs();
            if (progress > bestProgress) {
                bestProgress = progress;
                candidate = i;
            }
        }
        return candidate;
    }

    /**
     * 车道准入判据：该车道每条在途弹幕的已推进像素须 ≥ max(新条文本宽, 该条文本宽) + {@link #MIN_GAP_PX}。
     * 占位宽度取两者较大值——更宽的弹幕行程更快会追上较窄的前车，只用前车宽度算提前量会在最坏时刻追尾；
     * 已推进像素按行程比例换算成时间提前量（毫秒向上取整，让位不早于理论时刻），与画面时间无关（本地钟驱动）。
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        int pw, long now, long travelMs) {
        return laneReleased(actives, mode, lane, newWPx, pw, now, travelMs, -1);
    }

    /**
     * @param skipIndex 试算抢占腾位时忽略的在途条目下标（-1 表示不忽略）
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        int pw, long now, long travelMs, int skipIndex) {
        for (int i = 0; i < actives.size(); i++) {
            if (i == skipIndex) continue;
            Active active = actives.get(i);
            if (active.mode() != mode || active.lane() != lane) continue;
            float widest = Math.max(newWPx, active.textWPx());
            float span = pw + widest;
            long advance = (long) Math.ceil(travelMs * Math.min(1.0, (widest + MIN_GAP_PX) / span));
            if (now - active.startMono() < advance) return false;
        }
        return true;
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

    /** 条目寿命：滚动/逆向滚动按行程，固定项按驻留时长（固定模式不受 speedMultiplier 影响） */
    private static long lifetimeMs(Active active) {
        return switch (active.mode()) {
            case SCROLL, REVERSE -> active.travelMs();
            default -> PIN_HOLD_MS;
        };
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

    /** 来源与模式开关过滤：关闭的来源/模式在出队后即丢弃，不占容量闸；REVERSE 跟随滚动开关 */
    private static boolean isVisible(DanmakuEntry entry, ClientConfig config) {
        boolean sourceOn = switch (entry.source()) {
            case BILIBILI_VIDEO -> config.danmakuBilibiliVideo.get();
            case BILIBILI_LIVE -> config.danmakuBilibiliLive.get();
            case ROOM_CHAT -> config.danmakuRoomChat.get();
        };
        boolean modeOn = switch (entry.mode()) {
            case SCROLL, REVERSE -> config.danmakuShowScroll.get();
            case ADVANCED -> false;
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
