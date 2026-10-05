package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.client.danmaku.DanmakuWorldLayer.AnomalyLog;
import me.zuogeren.kazumiplayer.client.danmaku.DanmakuWorldLayer.LaneGeometry;
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
 * <p>时钟：条目按来源选驱动时钟——片内弹幕（BILIBILI_VIDEO）用视频播放位置，
 * 暂停即冻结（恢复后从停住处继续，不跳变）；房间互发与直播弹幕用本地单调钟，
 * 不受暂停影响。行程进度、驻留时长与车道让位判据的推进量都取该条自身的时钟。
 *
 * <p>布局按带组织，三条带几何互不影响：滚动带（SCROLL 右进左出 / REVERSE 左进右出，互为镜像）
 * 起点恒为显示区顶部并按车道轮转；TOP 带自顶部向下、BOTTOM 带自底部向上，槽位数 = 车道数
 * （由显示带高与车道高推导，见 {@link DanmakuWorldLayer.LaneGeometry#pinSlots()}，不写死行数），
 * <b>先占满全部可用行、用尽后才压叠</b>，压叠槽位按轮转游标分散（{@link DanmakuPinSlots}）；
 * 固定项居中驻留 {@link #PIN_HOLD_MS}（不受 speedMultiplier 影响）。带间重叠时固定项压在滚动项之上（B 站语义）。
 *
 * <p>车道准入是占用感知的：同车道每条在途弹幕的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) +
 * {@link #MIN_GAP_PX} 才允许新条入轨；占位宽度取两者的较大值，保证更宽的追随弹幕（行程更快）
 * 在最坏时刻也不会追上前车。单条缩放的实际上限由车道高度决定，大字号条目不溢出到相邻车道。
 *
 * <p>社交优先：出队后按来源优先级（ROOM_CHAT &gt; BILIBILI_LIVE &gt; BILIBILI_VIDEO）稳定排序再准入；
 * config.danmakuScreenCap() 容量闸只拦视频片内条目，社交条目不受上限约束（仍计入在屏数）；
 * 社交条目优先走空闲车道，其次抢占该车道里最接近离场的视频片内条目腾位，仍不足时放进「最空」
 * 的车道并允许与在途条目重叠（该条豁免 MIN_GAP 不变量，计入 forced-overlap DEBUG 计数）——
 * 即社交条目永不丢弃；视频片内条目按原规则丢弃。
 *
 * <p>坐标域：车道/槽位/滚动位移都在画面矩形 (px, py, pw, ph) 的像素域内，显示区为画面顶部
 * ph × danmakuAreaRatio 的横带（与世界层「显示区 = 屏高 × danmakuAreaRatio」同口径，见 {@link #displayAreaH}）。
 * 显示带下界按全屏控制条安全区内缩（调用方每帧传入控制条高与其上缘 {@code bottomReservePx}/
 * {@code bottomUiTopY}，口径见 {@link LaneGeometry#keepOut}）：滚动/顶部带自显示带上缘向下、底部带自
 * 内缩下界向上铺开，四条带（含文字框与 REVERSE 镜像）连同字形外扩保护都不进入控制条所占区域；
 * 内缩量只由控制条几何与字号决定，控制条鼠标静止淡出期间**照常保留**——弹幕落点不随控件显隐跳动
 * （换取淡出时底部空出一条与显隐无关的带，取舍见 plans/f11-danmaku-ui-safe-area.md）。
 * 车道几何随 danmakuFontScale 缩放（车道高 = 9×1.4×danmakuFontScale，
 * 车道数 = clamp(floor(可用下界 / 车道高), 1, 48)，与世界层共用 {@link DanmakuWorldLayer.LaneGeometry}
 * 同一实现），故字号缩小即行距收紧、车道变多且铺满显示区；可用高不足一条车道时按 1 条车道处理并整排
 * 上移（越出显示带上缘而不是越过控制条）。字号由 danmakuFontScale × 条目 fontSizePercent/100 作为逐条 pose
 * 缩放施加（比例自入场固定，不受在屏集合影响），几何量按该缩放折算成实际像素后参与定位与占位计算；
 * 字形按 {@link DanmakuWorldLayer#textTopLocal} 在车道内垂直居中（与世界层同一口径）。
 *
 * <p>逐帧成本：视觉序文本、绘制宽、pose 缩放、主色/描边色/框色与字形外接框都在准入时一次算好
 * （见 {@link Visual}），绘制循环只做取整与偏移算术、不重排文本也不再合成颜色，且不新建任何对象。
 *
 * <p>开关：来源三开关与模式三开关过滤出队条目；danmakuShowColored=false 时跳过颜色非白的
 * B 站条目（房间互发恒金字不受影响）；danmakuShowAdvanced=true 时把高级弹幕（B 站 mode 7/8/9）
 * 按其文本按时间降级为普通滚动，降级后与普通条目同样参与车道准入，false 则跳过。
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
    /** 白色弹幕过滤基准色（danmakuShowColored=false 时保留的 B 站条目颜色） */
    private static final int WHITE_RGB = 0xFFFFFF;

    /**
     * 准入时一次算好的绘制参数（绘制循环逐帧只读，零重算、零分配）。
     *
     * @param text         视觉序文本（入场时 {@code getVisualOrderText()} 一次，此后不再重排）
     * @param index        字符槽索引（前缀推进宽度 + 原始位置）：可见区间二分与子序列撇取都读它
     * @param textWPx      条目文本实际像素宽（基础字宽 × {@code scale}）
     * @param scale        条目 pose 缩放：danmakuFontScale × fontSizePercent/100，上限为单个车道高度
     * @param textTop      字形在车道内垂直居中的局部行顶 y（{@link DanmakuWorldLayer#textTopLocal}）
     * @param color        主色（已按 danmakuOpacity 合成 alpha；房间互发恒金）
     * @param outlineColor 描边色（已合成 alpha）
     * @param frameColor   文字框色（已合成 alpha）
     * @param frameInk     房间互发条目的字形外接框（{@code prepareText} 一次），其余来源为 null
     */
    private record Visual(FormattedCharSequence text, DanmakuWorldLayer.CharIndex index, float textWPx,
                          float scale, float textTop, int color, int outlineColor, int frameColor,
                          ScreenRectangle frameInk) {
        /** 字符槽数 */
        int chars() {
            return index.chars();
        }
    }

    /**
     * 在屏条目。
     *
     * @param startMs 该条驱动时钟在准入时刻的取值（社交=单调钟，片内=视频播放位置）
     */
    private record Active(DanmakuEntry entry, Visual visual, long startMs, DanmakuMode mode,
                          int travelMs, int lane) {}

    /** 帧时钟：社交弹幕（房间/直播）用单调钟，片内弹幕用视频播放位置——暂停期后者冻结 */
    private record Clocks(long monoMs, long videoMs) {
        long of(DanmakuSource source) {
            return isVideoDanmaku(source) ? videoMs : monoMs;
        }
    }

    /**
     * 车道分配结果。
     *
     * @param lane       可用车道下标（&lt; 0 表示无车道可入）
     * @param evictIndex 需先移除的在途条目下标（-1 表示无需腾位）
     * @param forced     社交条目被迫与在途条目重叠入轨（该条豁免 MIN_GAP 不变量）
     */
    private record LanePick(int lane, int evictIndex, boolean forced) {}

    /**
     * 准入结果。
     *
     * @param active  已登记条目（null 表示丢弃该条）
     * @param evicted 因抢占腾位被移除的在途条目（null 表示未抢占）
     * @param forced  社交条目被迫重叠入轨（无空闲车道且无可腾位视频）
     */
    private record Admission(Active active, Active evicted, boolean forced) {
        private static final Admission DROPPED = new Admission(null, null, false);
    }

    /** 来源优先级（数值越小越优先）：社交弹幕先占位，视频片内条目最低 */
    private static int sourcePriority(DanmakuSource source) {
        return switch (source) {
            case ROOM_CHAT -> 0;
            case BILIBILI_LIVE -> 1;
            case BILIBILI_VIDEO -> 2;
        };
    }

    /** 视频片内条目：受 danmakuScreenCap 容量闸限制，且可被更高优先级的弹幕抢占腾位 */
    private static boolean isVideoDanmaku(DanmakuSource source) {
        return source == DanmakuSource.BILIBILI_VIDEO;
    }

    /** 容量闸：只拦视频片内条目（社交条目不受 danmakuScreenCap 上限约束，始终优先上屏） */
    static boolean capBlocks(DanmakuSource source, int activeCount, ClientConfig config) {
        return isVideoDanmaku(source) && activeCount >= config.danmakuScreenCap();
    }

    /** B 站来源（片内/直播）：白色过滤开关只作用于这两类来源 */
    private static boolean isBilibiliDanmaku(DanmakuSource source) {
        return source == DanmakuSource.BILIBILI_VIDEO || source == DanmakuSource.BILIBILI_LIVE;
    }

    /** 彩色弹幕开关：关闭时跳过颜色非白的 B 站条目（房间互发恒金字，不受该开关影响） */
    private static boolean colorAllowed(DanmakuSource source, int colorRgb, boolean showColored) {
        return showColored || !isBilibiliDanmaku(source) || (colorRgb & 0xFFFFFF) == WHITE_RGB;
    }

    /**
     * 高级弹幕（B 站 mode 7/8/9：高级定位/代码/BAS）没有定位与代码字段：开启时按其文本按时间
     * 降级为普通滚动弹幕，关闭时跳过。
     *
     * @return 生效模式；null 表示跳过该条
     */
    private static DanmakuMode effectiveMode(DanmakuMode mode, boolean showAdvanced) {
        if (mode != DanmakuMode.ADVANCED) return mode;
        return showAdvanced ? DanmakuMode.SCROLL : null;
    }

    private static final Map<BlockPos, List<Active>> ACTIVE = new ConcurrentHashMap<>();
    /** 该屏上一帧用于到期判定的视频时间（场景态清理基线） */
    private static final Map<BlockPos, Long> LAST_VIDEO_TIME = new ConcurrentHashMap<>();
    /** 每屏一份异常日志聚合器（无异常帧不产出日志；随清屏/断线重置） */
    private static final Map<BlockPos, AnomalyLog> ANOMALY = new ConcurrentHashMap<>();
    /** 每屏上次见到的车道数：车道数变化（画面高/显示区/字号变化，含窗口缩放）即收口在屏条目的车道号 */
    private static final Map<BlockPos, Integer> LAST_LANES = new ConcurrentHashMap<>();
    /** 滚动车道轮转游标：多条同时可入时用于分散到不同车道 */
    private static int laneCursor;
    /** 固定槽位分配器（TOP/BOTTOM 各一份轮转游标）：有空槽必占空槽，槽位用尽后在各槽位间轮转分散压叠 */
    private static final DanmakuPinSlots PIN_TOP = new DanmakuPinSlots();
    private static final DanmakuPinSlots PIN_BOTTOM = new DanmakuPinSlots();
    private static boolean clearHookInstalled;

    private DanmakuHudLayer() {}

    /**
     * 全屏帧入口：pictureRect 为等比视频画面矩形（弹幕只在画面范围内滚动/驻留）。
     *
     * @param currentVideoTimeMs 该屏当前播放位置（暂停期为冻结位置）；片内弹幕的驱动时钟与到期判定都用它
     * @param bottomReservePx 底部 UI 预留（画面像素）：全屏控制条高，显示带下界按本值内缩；
     *                        控制条淡出期间照常传入，保证弹幕落点与控件显隐无关
     * @param bottomUiTopY    底部 UI（控制条）上缘的 GUI 纵坐标：显示带越过控制条上缘时据此收下界
     */
    public static void draw(GuiGraphicsExtractor g, Minecraft mc, BlockPos screenPos,
                            int px, int py, int pw, int ph, long currentVideoTimeMs,
                            int bottomReservePx, int bottomUiTopY) {
        var config = ClientConfig.CONFIG;
        if (!config.danmakuEnabled.get() || !config.danmakuShowInFullscreen.get()) return;
        installClearHook();

        long travelMs = scrollTravelMs();
        Clocks clocks = new Clocks(MonoClock.millis(), currentVideoTimeMs);

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
        // 车道几何随字号缩放：车道高 = 9×1.4×danmakuFontScale，车道数由显示区（画面顶部 ratio 带）
        // 扣除底部 UI 安全区后的可用下界推导（与世界层共用 keepOut 口径）
        float areaH = displayAreaH(ph, config.danmakuAreaRatio.get().floatValue());
        LaneGeometry lanes = LaneGeometry.of(areaH, fontScale,
            LaneGeometry.keepOut(areaH, bottomReservePx, bottomUiTopY - py));
        // 几何每帧由本帧 rect 重算（无按屏几何缓存）；尺寸/字号变化时把越界车道号收进最后一条车道
        Integer lastLanes = LAST_LANES.put(screenPos, lanes.lanes);
        if (lastLanes != null && lastLanes != lanes.lanes) {
            int moved = 0;
            for (int i = 0; i < actives.size(); i++) {
                Active active = actives.get(i);
                int clamped = DanmakuWorldLayer.clampLane(active.lane(), lanes.lanes);
                if (clamped == active.lane()) continue;
                actives.set(i, new Active(active.entry(), active.visual(), active.startMs(),
                    active.mode(), active.travelMs(), clamped));
                moved++;
            }
            KazumiLog.danmaku.debug("HUD layer geometry changed at {}: lanes {} -> {}, reclamped {} of {}",
                screenPos, lastLanes, lanes.lanes, moved, actives.size());
        }
        // 颜色与文字框几何都在准入时一次算好（每帧只读缓存值）
        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int alpha = Math.max(0, Math.min(255, Math.round(alphaFactor * 255.0f)));
        int outlineColorBase = ARGB.black(alpha);
        int frameColorBase = ARGB.color(alpha, ROOM_CHAT_COLOR);

        boolean allowOverlap = config.danmakuAllowOverlap.get();
        int accepted = 0;
        int noLane = 0;
        int capped = 0;
        int evicted = 0;
        int forced = 0;
        int forcedVideo = 0;
        int skipped = 0;
        for (DanmakuEntry entry : due) {
            // 高级弹幕降级为普通滚动（关闭时跳过），降级后与普通条目同样参与车道准入
            DanmakuMode mode = effectiveMode(entry.mode(), config.danmakuShowAdvanced.get());
            if (mode == null) {
                skipped++;
                continue;
            }
            if (!isVisible(entry, mode, config)) continue;
            // 容量闸只拦视频片内条目：社交条目不受上限约束（仍计入 actives、仍受车道可用性约束）
            if (capBlocks(entry.source(), actives.size(), config)) {
                capped++;
                continue;
            }
            FormattedCharSequence text = entryText(entry);
            float scalePercent = Math.max(10, entry.fontSizePercent())
                / (float) DanmakuEntry.FONT_SIZE_STANDARD;
            // 缩放上限取单条车道高度：大字号条目在自己的车道内放大，不压到相邻车道
            float entryScale = Math.min(fontScale * scalePercent, lanes.maxEntryScale());
            float drawWPx = font.width(text) * entryScale;
            // 字形在车道内垂直居中：与世界层同一口径，缩放后仍不越出本车道与底部 UI 内缩下界
            float textTop = DanmakuWorldLayer.textTopLocal(entryScale, lanes.laneH);
            int color = entryColor(entry, alphaFactor);
            // 字符槽索引：可见区间裁剪一次建表；超长条目另记一条 DEBUG 便于定位异常弹幕
            DanmakuWorldLayer.CharIndex index = DanmakuWorldLayer.indexChars(font, text);
            DanmakuWorldLayer.logLongEntry(index.chars(), entry.source(), screenPos);
            Visual visual = new Visual(text, index, drawWPx, entryScale, textTop, color, outlineColorBase,
                frameColorBase, entry.source() == DanmakuSource.ROOM_CHAT
                    ? inkBounds(font, text, textTop, color, !outline) : null);
            Admission adm = admit(entry, mode, visual, actives, clocks, travelMs, pw, lanes,
                allowOverlap);
            if (adm.active() == null) {
                noLane++;
                continue;
            }
            if (adm.evicted() != null) evicted++;
            if (adm.forced()) {
                if (isVideoDanmaku(entry.source())) forcedVideo++;
                else forced++;
            }
            actives.add(adm.active());
            accepted++;
        }
        // 每帧异常才输出：首个异常帧一条明细，其后每 5 秒至多一条累计行；无异常帧完全静默
        AnomalyLog.Report report = ANOMALY.computeIfAbsent(screenPos, k -> new AnomalyLog())
            .submit(clocks.monoMs(), due.size(), accepted, noLane, capped, evicted, forced, forcedVideo,
                skipped);
        if (report != null) DanmakuWorldLayer.logAnomalies("HUD layer", screenPos, report, actives.size());

        for (int i = actives.size() - 1; i >= 0; i--) {
            Active active = actives.get(i);
            if (clocks.of(active.entry().source()) - active.startMs() >= lifetimeMs(active)) {
                actives.remove(i);
            }
        }
        if (actives.isEmpty()) {
            ACTIVE.remove(screenPos);
            return;
        }

        // 文字框整批先于文字提交：框恒在文字下层（同一层内矩形先于字形绘制）
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.visual().frameInk() == null) continue;
            drawFrame(g, active, clocks, px, py, pw, lanes, active.visual().frameColor());
        }

        // 分带提交：滚动带先画，TOP/BOTTOM 后画 → 三条带重叠处固定项压在上层
        drawBand(g, font, actives, DanmakuMode.SCROLL, clocks, px, py, pw, outline, lanes);
        drawBand(g, font, actives, DanmakuMode.REVERSE, clocks, px, py, pw, outline, lanes);
        drawBand(g, font, actives, DanmakuMode.TOP, clocks, px, py, pw, outline, lanes);
        drawBand(g, font, actives, DanmakuMode.BOTTOM, clocks, px, py, pw, outline, lanes);
    }

    /**
     * 单条带的提交：滚动带与逆向滚动带起点恒为显示区顶部（车道几何不受固定项影响），
     * TOP 自顶部向下、BOTTOM 自底部向上。
     */
    private static void drawBand(GuiGraphicsExtractor g, Font font, List<Active> actives, DanmakuMode band,
                                 Clocks clocks, int px, int py, int pw, boolean outline,
                                 LaneGeometry lanes) {
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() != band) continue;
            Visual visual = active.visual();
            float x = posX(active, clocks, pw);
            float drawWPx = visual.textWPx();
            // 整条移出显示区即不再提交（滚动/逆向滚动出界后无可见部分）
            if (isOutside(x, drawWPx, pw)) continue;
            // 部分可见：只提交可见字符槽区间（前缀宽度二分，绘制期不重算全串宽度）
            long range = DanmakuWorldLayer.visibleSlots(visual.index().prefix(), visual.scale(),
                Math.max(0.0f, -x), Math.min(drawWPx, pw - x));
            int start = DanmakuWorldLayer.rangeStart(range);
            int end = DanmakuWorldLayer.rangeEnd(range);
            if (start >= end) continue;
            boolean clipped = start > 0 || end < visual.chars();
            FormattedCharSequence seq = clipped
                ? DanmakuWorldLayer.slice(visual.text(), visual.index().positions(), start, end)
                : visual.text();
            // 子序列左端对齐：平移量折进 pose（屏幕域 = pose 缩放 × 未缩放位移），文字局部坐标仍是整型
            float shift = clipped ? visual.index().prefix()[start] * visual.scale() : 0.0f;
            float y = posY(active, lanes);
            // 按条目各自缩放：比例自入场固定，任一条目上下场都不改变他人的位置与大小；
            // 缩放内所有坐标与偏移量都处于该条目的局部像素域，主字/描边/文字框天然同尺度
            g.pose().pushMatrix();
            g.pose().translate(px + x + shift, py + y);
            g.pose().scale(visual.scale(), visual.scale());
            // 字号缩放后行顶仍是浮点，GUI 文本 API 只收整型坐标 → 取整到最近像素
            int textTopPx = Math.round(visual.textTop());
            if (outline) {
                int outlineColor = visual.outlineColor();
                for (int[] offset : OUTLINE_OFFSETS) {
                    g.text(font, seq, offset[0], textTopPx + offset[1], outlineColor, false);
                }
                g.text(font, seq, 0, textTopPx, visual.color(), false);
            } else {
                g.text(font, seq, 0, textTopPx, visual.color(), true);
            }
            g.pose().popMatrix();
        }
    }

    /**
     * 空心文字框：画面像素域四条 {@link #FRAME_EDGE} 细边，框内不填充，随条目车道/位移同步。
     * 矩形由准入时缓存的字形外接框（{@link Visual#frameInk}）四边各外扩 {@link #FRAME_PAD} 像素推导，
     * 故文字在框内水平与垂直都居中，边框不压在字形上；逐帧只做四次取整与四条 fill，无分配。
     */
    private static void drawFrame(GuiGraphicsExtractor g, Active active, Clocks clocks, int px, int py,
                                  int pw, LaneGeometry lanes, int color) {
        ScreenRectangle ink = active.visual().frameInk();
        if (ink == null) return;
        float x = posX(active, clocks, pw);
        if (isOutside(x, active.visual().textWPx(), pw)) return;
        float q = active.visual().scale();
        float yTop = posY(active, lanes);
        // 文字框同时裁到画面矩形内：屏幕外的框边不再提交
        int left = Math.max(Math.round(px + x + ink.left() * q - FRAME_PAD), px);
        int top = Math.round(py + yTop + ink.top() * q - FRAME_PAD);
        int right = Math.min(Math.round(px + x + ink.right() * q + FRAME_PAD), px + pw);
        // 文字框同时裁到画面矩形与底部 UI 内缩下界内：控制条区内的框边不再提交
        int bottom = Math.min(Math.round(py + yTop + ink.bottom() * q + FRAME_PAD),
            py + Math.round(lanes.bottomLimit));
        if (right <= left || bottom <= top) return;
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

    /** 条目左上角在画面内的横向位置（画面像素域）：SCROLL 右进左出、REVERSE 左进右出（镜像） */
    private static float posX(Active active, Clocks clocks, int pw) {
        float drawWPx = active.visual().textWPx();
        // 固定项居中驻留，与行程无关
        if (active.mode() == DanmakuMode.TOP || active.mode() == DanmakuMode.BOTTOM) {
            return (pw - drawWPx) / 2.0f;
        }
        float progress = progress(active, clocks);
        // REVERSE 为镜像：p=0 时文字整体藏于左缘外，p=1 时文字尾越过右缘
        return active.mode() == DanmakuMode.REVERSE
            ? progress * (pw + drawWPx) - drawWPx
            : pw - progress * (pw + drawWPx);
    }

    /** 行程进度 p∈[0,1]：按条目自身时钟推进（片内弹幕暂停即冻结；固定项 travelMs=0 不参与行程） */
    private static float progress(Active active, Clocks clocks) {
        int travelMs = active.travelMs();
        if (travelMs <= 0) return 0.0f;
        return (clocks.of(active.entry().source()) - active.startMs()) / (float) travelMs;
    }

    /** 条目所在车道的纵向位置（画面像素域）：底部带自显示区下缘往上，其余带自显示区顶部往下 */
    private static float posY(Active active, LaneGeometry lanes) {
        return active.mode() == DanmakuMode.BOTTOM
            ? lanes.bottomOf(active.lane())
            : lanes.topOf(active.lane());
    }

    /** 条目整条移出显示区（滚动/逆向滚动的出场态），固定项居中恒在显示区内 */
    private static boolean isOutside(float x, float drawWPx, int pw) {
        return x > pw || x + drawWPx < 0;
    }

    /**
     * 单条准入：占用感知地分配车道/槽位。
     *
     * <p>社交条目永不丢弃：抢占腾位时会从 actives 就地移除被抢占条目，仍无空位则被迫重叠入轨。
     *
     * @param visual       准入时算好的绘制参数（文本/宽度/缩放/配色/文字框外接框）
     * @param lanes        本帧车道几何（车道高与车道数随字号缩放）
     * @param allowOverlap danmakuAllowOverlap：开启后视频片内条目无空车道时压叠上屏而非丢弃
     * @return 准入结果；active 为 null 表示丢弃该条
     */
    private static Admission admit(DanmakuEntry entry, DanmakuMode mode, Visual visual,
                                   List<Active> actives, Clocks clocks, long travelMs, int pw,
                                   LaneGeometry lanes, boolean allowOverlap) {
        int pinSlots = lanes.pinSlots();
        long startMs = clocks.of(entry.source());
        return switch (mode) {
            case TOP -> pinAdmit(entry, visual, actives, DanmakuMode.TOP, startMs, pinSlots,
                allowOverlap);
            case BOTTOM -> pinAdmit(entry, visual, actives, DanmakuMode.BOTTOM, startMs, pinSlots,
                allowOverlap);
            case ADVANCED -> Admission.DROPPED;
            case SCROLL, REVERSE -> {
                LanePick pick = pickLane(actives, mode, visual.textWPx(), pw, clocks, travelMs,
                    !isVideoDanmaku(entry.source()), allowOverlap, lanes.lanes);
                if (pick.lane() < 0) yield Admission.DROPPED;
                Active victim = pick.evictIndex() >= 0 ? actives.remove(pick.evictIndex()) : null;
                yield new Admission(new Active(entry, visual, clocks.of(entry.source()), mode,
                    (int) travelMs, pick.lane()), victim, pick.forced());
            }
        };
    }

    /**
     * 固定项准入：槽位数 = 车道数（由显示带高与车道高推导，见 {@link DanmakuWorldLayer.LaneGeometry#pinSlots()}）。
     * 有空槽必占空槽；槽位用尽后社交条目恒压叠、视频片内条目仅在 danmakuAllowOverlap 开启时压叠，
     * 压叠槽位由 {@link DanmakuPinSlots} 按轮转游标在各槽位间分散（不再固定挑「最接近释放」的同一槽），
     * 口径与滚动车道一致；无槽可用（槽位数为 0）时丢弃。
     */
    private static Admission pinAdmit(DanmakuEntry entry, Visual visual, List<Active> actives,
                                      DanmakuMode pinMode, long startMs, int pinSlots,
                                      boolean allowOverlap) {
        boolean[] used = pinUsage(actives, pinMode, pinSlots);
        DanmakuPinSlots.Pick pick = pinAllocator(pinMode).allocate(used);
        if (pick.slot() < 0) return Admission.DROPPED;
        if (pick.forced() && isVideoDanmaku(entry.source()) && !allowOverlap) return Admission.DROPPED;
        return new Admission(new Active(entry, visual, startMs, pinMode, 0, pick.slot()), null,
            pick.forced());
    }

    /**
     * 车道分配三遍：1) 按轮转游标找已让出空间的车道；2) 社交条目按同一轮转序试算抢占——移除该车道
     * 里行程 progress 最大的视频片内条目后确实让出空间才腾位（不成立不白丢视频条目）；
     * 3) 社交条目仍无位时放进「最空」的车道（该车道最后一条已推进像素最多者，重叠量最小）并标记被迫重叠。
     * 视频片内条目只走第一遍，无位即丢弃。
     *
     * @param social       该条是否为社交条目（房间互发/直播）：可抢占、恒可压叠
     * @param allowOverlap danmakuAllowOverlap：视频片内条目是否允许压叠上屏（社交条目不受该开关影响）
     * @param laneCount    本帧车道数（随字号缩放）
     */
    private static LanePick pickLane(List<Active> actives, DanmakuMode mode, float newWPx, int pw,
                                     Clocks clocks, long travelMs, boolean social, boolean allowOverlap,
                                     int laneCount) {
        for (int offset = 0; offset < laneCount; offset++) {
            int lane = Math.floorMod(laneCursor + offset, laneCount);
            if (laneReleased(actives, mode, lane, newWPx, pw, clocks, travelMs)) {
                laneCursor = Math.floorMod(lane + 1, laneCount);
                return new LanePick(lane, -1, false);
            }
        }
        // 抢占腾位是社交条目特权（视频条目不允许挤掉别人）
        if (social) {
            for (int offset = 0; offset < laneCount; offset++) {
                int lane = Math.floorMod(laneCursor + offset, laneCount);
                int victim = evictCandidate(actives, mode, lane, clocks);
                if (victim < 0) continue;
                if (!laneReleased(actives, mode, lane, newWPx, pw, clocks, travelMs, victim)) continue;
                laneCursor = Math.floorMod(lane + 1, laneCount);
                return new LanePick(lane, victim, false);
            }
        }
        // 压叠兜底：社交条目恒定可用；视频片内条目仅在 danmakuAllowOverlap 开启时可用
        if (!social && !allowOverlap) return new LanePick(-1, -1, false);
        int bestLane = -1;
        float mostAdvance = -1.0f;
        for (int lane = 0; lane < laneCount; lane++) {
            float advance = lastAdvance(actives, mode, lane, pw, clocks, travelMs);
            if (advance > mostAdvance) {
                mostAdvance = advance;
                bestLane = lane;
            }
        }
        if (bestLane < 0) return new LanePick(-1, -1, false);
        laneCursor = Math.floorMod(bestLane + 1, laneCount);
        return new LanePick(bestLane, -1, true);
    }

    /** 抢占候选：该车道里行程 progress 最大（最接近离场）的视频片内条目下标；没有则 -1 */
    private static int evictCandidate(List<Active> actives, DanmakuMode mode, int lane, Clocks clocks) {
        int candidate = -1;
        float bestProgress = -1.0f;
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() != mode || active.lane() != lane) continue;
            if (!isVideoDanmaku(active.entry().source())) continue;
            float progress = progress(active, clocks);
            if (progress > bestProgress) {
                bestProgress = progress;
                candidate = i;
            }
        }
        return candidate;
    }

    /** 该车道最后一条（行程推进最少）在途弹幕的已推进像素；车道为空返回 -1 */
    private static float lastAdvance(List<Active> actives, DanmakuMode mode, int lane, int pw,
                                     Clocks clocks, long travelMs) {
        float newestElapsed = Float.MAX_VALUE;
        float newestWidth = -1.0f;
        for (Active active : actives) {
            if (active.mode() != mode || active.lane() != lane) continue;
            float elapsed = clocks.of(active.entry().source()) - active.startMs();
            if (elapsed < newestElapsed) {
                newestElapsed = elapsed;
                newestWidth = active.visual().textWPx();
            }
        }
        if (newestWidth < 0) return -1.0f;
        return newestElapsed / travelMs * (pw + newestWidth);
    }

    /**
     * 车道准入判据：该车道每条在途弹幕的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) + {@link #MIN_GAP_PX}。
     * 占位宽度取两者较大值——更宽的弹幕行程更快会追上较窄的前车，只用前车宽度算提前量会在最坏时刻追尾；
     * 推进量按该条自身时钟计（片内弹幕暂停期不推进，不会被误判为已让位）。
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        int pw, Clocks clocks, long travelMs) {
        return laneReleased(actives, mode, lane, newWPx, pw, clocks, travelMs, -1);
    }

    /**
     * @param skipIndex 试算抢占腾位时忽略的在途条目下标（-1 表示不忽略）
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        int pw, Clocks clocks, long travelMs, int skipIndex) {
        for (int i = 0; i < actives.size(); i++) {
            if (i == skipIndex) continue;
            Active active = actives.get(i);
            if (active.mode() != mode || active.lane() != lane) continue;
            float widest = Math.max(newWPx, active.visual().textWPx());
            float span = pw + widest;
            long advance = (long) Math.ceil(travelMs * Math.min(1.0, (widest + MIN_GAP_PX) / span));
            if (clocks.of(active.entry().source()) - active.startMs() < advance) return false;
        }
        return true;
    }

    /**
     * 固定槽位占用情况（长度 = 本帧槽位数）：被在途条目占用的槽位不可复用；
     * 越界车道号（几何缩水后尚未收口）不计入占用，与 {@link DanmakuWorldLayer#clampLane} 同口径。
     */
    private static boolean[] pinUsage(List<Active> actives, DanmakuMode mode, int pinSlots) {
        boolean[] used = new boolean[pinSlots];
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() == mode && active.lane() >= 0 && active.lane() < pinSlots) {
                used[active.lane()] = true;
            }
        }
        return used;
    }

    /** 固定槽位分配器（TOP/BOTTOM 各一份游标；两层各持自己的实例，故同输入逐帧同输出） */
    private static DanmakuPinSlots pinAllocator(DanmakuMode mode) {
        return mode == DanmakuMode.TOP ? PIN_TOP : PIN_BOTTOM;
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

    /** 来源/模式/颜色开关过滤：关闭的来源、模式与非白 B 站条目在出队后即丢弃，不占容量闸 */
    private static boolean isVisible(DanmakuEntry entry, DanmakuMode mode, ClientConfig config) {
        boolean sourceOn = switch (entry.source()) {
            case BILIBILI_VIDEO -> config.danmakuBilibiliVideo.get();
            case BILIBILI_LIVE -> config.danmakuBilibiliLive.get();
            case ROOM_CHAT -> config.danmakuRoomChat.get();
        };
        boolean modeOn = switch (mode) {
            case SCROLL, REVERSE -> config.danmakuShowScroll.get();
            case TOP -> config.danmakuShowTop.get();
            case BOTTOM -> config.danmakuShowBottom.get();
            case ADVANCED -> false;
        };
        return sourceOn && modeOn
            && colorAllowed(entry.source(), entry.colorRgb(), config.danmakuShowColored.get());
    }

    /** 断线/卸载世界时清空活动列表（退出全屏后 draw 不再被调，滞留条目靠此钩子回收） */
    public static void reset() {
        ACTIVE.clear();
        LAST_VIDEO_TIME.clear();
        ANOMALY.clear();
        LAST_LANES.clear();
        PIN_TOP.reset();
        PIN_BOTTOM.reset();
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
            ANOMALY.remove(pos);
            LAST_LANES.remove(pos);
        });
    }

    /** 显示区高（画面像素）：与世界层同口径——等比画面高 × danmakuAreaRatio，弹幕只在该横带内滚动/驻留 */
    static float displayAreaH(int ph, float areaRatio) {
        return ph * areaRatio;
    }

    /** 滚动行程：基准 5s 除以速度倍率（所有滚动项同一速度，与弹幕数量无关） */
    private static long scrollTravelMs() {
        double speed = ClientConfig.CONFIG.danmakuSpeedMultiplier.get();
        return (long) (ClientDanmakuStore.INSTANT_DISPLAY_MS / speed);
    }
}
