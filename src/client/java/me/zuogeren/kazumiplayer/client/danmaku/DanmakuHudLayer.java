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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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
 * 不受暂停影响。行程进度、驻留时长与水平让位判据的推进量都取该条自身的时钟。
 *
 * <p>布局按带组织，各带几何互不影响：滚动/逆向条目（SCROLL 右进左出 / REVERSE 左进右出，互为镜像）
 * 的纵向落点是<b>连续</b>的（{@link DanmakuPack}，与世界层共用同一实现）：落点由在途条目的边界推导，
 * 与「共存期间可能发生水平重叠」的在途条目保持至少 {@link DanmakuPack#MIN_SEP_PX} 像素的纵向间隔，
 * 故行距 = 条目自身文本框高 + 间隔，不落在整行网格上；TOP 带自显示区顶部向下、BOTTOM 带自底部向上，
 * 槽位数 = 车道数（由显示带高与车道高推导，见 {@link DanmakuWorldLayer.LaneGeometry#pinSlots()}，
 * 不写死行数），<b>先占满全部可用行、用尽后才压叠</b>，压叠槽位按轮转游标分散（{@link DanmakuPinSlots}）；
 * 固定项居中驻留 {@link #PIN_HOLD_MS}（不受 speedMultiplier 影响）。带间重叠时固定项压在滚动项之上（B 站语义）。
 *
 * <p>水平判据是占用感知的：同向在途条目的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) +
 * {@link DanmakuPack#MIN_GAP_PX} 才算让出（按行程比例换算成时间提前量、毫秒向上取整）；
 * 占位宽度取两者的较大值，保证更宽的追随弹幕（行程更快）在最坏时刻也不会追上前车；
 * 异向滚动条目必然中途交会，一律纵向分离。单条缩放的实际上限由车道高决定，大字号条目不溢出到相邻槽位。
 *
 * <p>社交优先：出队后按来源优先级（ROOM_CHAT &gt; BILIBILI_LIVE &gt; BILIBILI_VIDEO）稳定排序再准入；
 * config.danmakuScreenCap() 容量闸只拦视频片内条目，社交条目不受上限约束（仍计入在屏数）；
 * 滚动/逆向的社交条目优先取无碰撞落点，其次按行程进度降序试算抢占视频片内条目腾位（移除后确实腾出
 * 落点才腾位、不白丢视频条目），仍无落点时与在途条目纵向压叠（该条豁免水平让位不变量，计入
 * forced-overlap DEBUG 计数）——即社交条目永不丢弃；视频片内条目无落点时按压叠开关压叠或丢弃。
 *
 * <p>坐标域：落点/槽位/滚动位移都在画面矩形 (px, py, pw, ph) 的像素域内，显示区为画面顶部
 * ph × danmakuAreaRatio 的横带（与世界层「显示区 = 屏高 × danmakuAreaRatio」同口径，见 {@link #displayAreaH}）。
 * 显示带下界按全屏控制条安全区内缩（调用方每帧传入控制条高与其上缘 {@code bottomReservePx}/
 * {@code bottomUiTopY}，口径见 {@link LaneGeometry#keepOut}）：滚动/顶部带自显示带上缘向下、底部带自
 * 内缩下界向上铺开，四条带（含文字框与 REVERSE 镜像）连同字形外扩保护都不进入控制条所占区域；
 * 内缩量只由控制条几何与字号决定，控制条鼠标静止淡出期间**照常保留**——弹幕落点不随控件显隐跳动
 * （换取淡出时底部空出一条与显隐无关的带，取舍见 plans/f11-danmaku-ui-safe-area.md）。
 * 固定槽位几何随 danmakuFontScale 缩放（车道高 = 9×1.4×danmakuFontScale，
 * 车道数 = clamp(floor(可用下界 / 车道高), 1, 48)，与世界层共用 {@link DanmakuWorldLayer.LaneGeometry}
 * 同一实现），故字号缩小即槽位变多且铺满显示区（滚动/逆向条目的行距另由自身文本框高决定，
 * 见 {@link DanmakuPack}）；可用高不足一条车道时按 1 条车道处理并整排
 * 上移（越出显示带上缘而不是越过控制条）。字号由 danmakuFontScale × 条目 fontSizePercent/100 作为逐条 pose
 * 缩放施加（比例自入场固定，不受在屏集合影响），几何量按该缩放折算成实际像素后参与定位与占位计算；
 * 固定项字形按 {@link DanmakuWorldLayer#textTopLocal} 在槽位内垂直居中、滚动/逆向条目的行顶即落点上缘
 * （与世界层同一口径）。
 *
 * <p>逐帧成本：视觉序文本（含描边投影样式）、绘制宽、pose 缩放、主色/框色与字形外接框都在准入时
 * 一次算好（见 {@link Visual}），绘制循环只做取整与偏移算术、不重排文本也不再合成颜色，且不新建任何对象；
 * 每条在屏条目的文本绘制调用恒为 1 次（主字与其描边由同一个字形元素产出）。
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
 * <p>描边：GUI 侧文本 API 不带 outlineColor 形参（26.1.2 mojmap {@code GuiGraphicsExtractor}
 * L241-L267 六个 {@code text} 重载只到 color/dropShadow——String/FormattedCharSequence/Component
 * 各两档，{@code GuiTextRenderState} 构造亦无该形参；NeoForge 扩展只补了
 * {@code submitGuiElementRenderState}/{@code peekScissorStack}），故描边走原版<b>字形投影</b>通道：
 * 条目文本在准入时挂 {@link #OUTLINE_SHADOW_STYLE}（纯黑、alpha 255），
 * {@code Font$PreparedTextBuilder#getShadowColor} 把该色按主字 alpha 缩放成字形投影色，
 * 一次 {@code text} 调用即产出主字 + 1px 右下描边（{@code BakedSheetGlyph$GlyphInstance#renderChar}
 * 在同一字形元素内先画投影四边形再画主字），故描边与主字同一 alpha 基准、alpha 由 danmakuOpacity 缩放；
 * 描边色 alpha 传 255 而非已缩放值——{@code getShadowColor} 会再乘一次主字 alpha（传缩放值会平方）。
 * 世界层走 {@code SubmitNodeCollector#submitText} 的 outlineColor（原版八向描边），两层机制不同：
 * 全屏层为单次右下 1px 阴影、世界层为八向描边（出处与取舍见
 * plans/f11-danmaku-outline-parity.md）。
 * 文字框在画面像素域自绘四条 {@link #FRAME_EDGE} 细边（{@code fill}），框内不填充；
 * 整批框先于整批文字提交，GUI 状态同一层内矩形先于字形绘制，故框恒在文字下层。
 *
 * <p>场景态清理（f10 裁定 A 的渲染侧责任）：本层自记该屏上一帧视频时间，|Δ| 超过
 * {@link ClientDanmakuStore#SEEK_DETECT_MS} 时清空该屏 ACTIVE，避免 seek 后旧弹幕继续飘完行程。
 */
public final class DanmakuHudLayer {

    /** 顶部/底部项驻留时长（毫秒）：固定模式不受 speedMultiplier 影响 */
    private static final long PIN_HOLD_MS = 4500L;
    /**
     * 描边色（原版字形投影通道）：纯黑、alpha 255——字形侧按主字 alpha 缩放出实际描边 alpha，
     * 故描边强度恒与主字同源（{@code Font$PreparedTextBuilder#getShadowColor}）。
     */
    private static final Style OUTLINE_SHADOW_STYLE = Style.EMPTY.withShadowColor(ARGB.black(255));
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 房间互发弹幕文字框内边距（画面像素，四边相等，与 {@link DanmakuPack} 的占位口径同源）与线宽 */
    private static final int FRAME_PAD = (int) DanmakuPack.FRAME_PAD_PX;
    private static final int FRAME_EDGE = 1;
    /** 白色弹幕过滤基准色（danmakuShowColored=false 时保留的 B 站条目颜色） */
    private static final int WHITE_RGB = 0xFFFFFF;

    /**
     * 准入时一次算好的绘制参数（绘制循环逐帧只读，零重算、零分配）。
     *
     * @param text         视觉序文本（入场时 {@code getVisualOrderText()} 一次，此后不再重排；
     *                     描边开启时已带 {@link #OUTLINE_SHADOW_STYLE} 投影色——子序列撇取原样透传样式）
     * @param index        字符槽索引（前缀推进宽度 + 原始位置）：可见区间二分与子序列撇取都读它
     * @param textWPx      条目文本实际像素宽（基础字宽 × {@code scale}）
     * @param scale        条目 pose 缩放：danmakuFontScale × fontSizePercent/100，上限为单个车道高度
     * @param textTop      字形在车道内垂直居中的局部行顶 y（{@link DanmakuWorldLayer#textTopLocal}）
     * @param color        主色（已按 danmakuOpacity 合成 alpha；房间互发恒金）——描边 alpha 亦由它决定
     * @param frameColor   文字框色（已合成 alpha）
     * @param frameInk     房间互发条目的字形外接框（{@code prepareText} 一次，含投影），其余来源为 null
     */
    private record Visual(FormattedCharSequence text, DanmakuWorldLayer.CharIndex index, float textWPx,
                          float scale, float textTop, int color, int frameColor,
                          ScreenRectangle frameInk) {
        /** 字符槽数 */
        int chars() {
            return index.chars();
        }
    }

    /**
     * 在屏条目。
     *
     * @param startMs  该条驱动时钟在准入时刻的取值（社交=单调钟，片内=视频播放位置）
     * @param travelMs 滚动行程（固定项为 0）
     * @param slot     固定项槽位号（滚动/逆向为 -1）
     * @param top      滚动/逆向条目的文本框上缘（显示区坐标）；固定项不用
     * @param height   滚动/逆向条目的文本框高（字形行高 × 缩放）；固定项不用
     */
    private record Active(DanmakuEntry entry, Visual visual, long startMs, DanmakuMode mode,
                          int travelMs, int slot, float top, float height)
        implements DanmakuPack.Item {

        @Override
        public DanmakuSource source() {
            return entry.source();
        }

        @Override
        public float scale() {
            return visual.scale();
        }

        @Override
        public float width() {
            return visual.textWPx();
        }

        /** 槽位收口后的副本（几何变化时才新建） */
        Active withSlot(int newSlot) {
            return new Active(entry, visual, startMs, mode, travelMs, newSlot, top, height);
        }

        /** 落点收口后的副本（几何变化时才新建） */
        Active withTop(float newTop) {
            return new Active(entry, visual, startMs, mode, travelMs, slot, newTop, height);
        }
    }

    /** 帧时钟：社交弹幕（房间/直播）用单调钟，片内弹幕用视频播放位置——暂停期后者冻结 */
    private record Clocks(long monoMs, long videoMs) {
        long of(DanmakuSource source) {
            return isVideoDanmaku(source) ? videoMs : monoMs;
        }
    }

    /**
     * 准入结果。
     *
     * @param active  已登记条目（null 表示丢弃该条）
     * @param evicted 因抢占腾位被移除的在途条目（null 表示未抢占）
     * @param forced  社交条目被迫纵向压叠入屏（无落点且无可腾位视频）
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
    /** 每屏上次见到的车道数：仅在变化时记一条几何收口 DEBUG（收口本身每帧按当前几何做） */
    private static final Map<BlockPos, Integer> LAST_LANES = new ConcurrentHashMap<>();
    /** 滚动/逆向条目的连续纵向打包器（每层一份实例：暂存数组独立，互不影响） */
    private static final DanmakuPack PACK = new DanmakuPack();
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
        // 尺寸变化失效：几何每帧重算（无按屏缓存），在屏条目的纵向坐标按本帧几何收口——
        // 滚动/逆向的落点收进 [0, 可用下界 − 条目高]、固定项的槽位收进 [0, 车道数−1]
        int moved = refitActives(actives, lanes);
        Integer lastLanes = LAST_LANES.put(screenPos, lanes.lanes);
        if (moved > 0 || (lastLanes != null && lastLanes != lanes.lanes)) {
            KazumiLog.danmaku.debug(
                "HUD layer geometry refit at {}: lanes {} -> {}, bottomLimit {}, clamped {} of {}",
                screenPos, lastLanes, lanes.lanes, lanes.bottomLimit, moved, actives.size());
        }
        // 颜色与文字框几何都在准入时一次算好（每帧只读缓存值）
        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int alpha = Math.max(0, Math.min(255, Math.round(alphaFactor * 255.0f)));
        int frameColorBase = ARGB.color(alpha, ROOM_CHAT_COLOR);

        // 允许压叠：开关开启，或密度档位本身就是「重叠」（该档位语义即"不丢视频弹幕"，与开关同义）
        boolean allowOverlap = config.danmakuAllowOverlap.get()
            || config.danmakuDensity.get() == ClientConfig.DanmakuDensity.OVERLAP;
        int accepted = 0;
        int noLane = 0;
        int capped = 0;
        int evicted = 0;
        int forced = 0;
        int forcedVideo = 0;
        int skipped = 0;
        for (DanmakuEntry entry : due) {
            // 高级弹幕降级为普通滚动（关闭时跳过），降级后与普通条目同样参与落点准入
            DanmakuMode mode = effectiveMode(entry.mode(), config.danmakuShowAdvanced.get());
            if (mode == null) {
                skipped++;
                continue;
            }
            if (!isVisible(entry, mode, config)) continue;
            // 容量闸只拦视频片内条目：社交条目不受上限约束（仍计入 actives、仍受落点可用性约束）
            // 压叠模式下不设上限：上限先于落点准入，若仍生效则压叠兜底永远轮不到
            if (!allowOverlap && capBlocks(entry.source(), actives.size(), config)) {
                capped++;
                continue;
            }
            FormattedCharSequence text = entryText(entry, outline);
            float scalePercent = Math.max(10, entry.fontSizePercent())
                / (float) DanmakuEntry.FONT_SIZE_STANDARD;
            // 缩放上限取单条车道高：大字号条目按自身尺寸放大，但不越出固定槽位、也不多占纵向空间
            float entryScale = Math.min(fontScale * scalePercent, lanes.maxEntryScale());
            float drawWPx = font.width(text) * entryScale;
            // 字形行顶：固定项在槽位内垂直居中，滚动/逆向条目的文本框上缘即落点（与世界层同一口径）
            float textTop = mode == DanmakuMode.SCROLL || mode == DanmakuMode.REVERSE
                ? 0.0f : DanmakuWorldLayer.textTopLocal(entryScale, lanes.laneH);
            int color = entryColor(entry, alphaFactor);
            // 字符槽索引：可见区间裁剪一次建表；超长条目另记一条 DEBUG 便于定位异常弹幕
            DanmakuWorldLayer.CharIndex index = DanmakuWorldLayer.indexChars(font, text);
            DanmakuWorldLayer.logLongEntry(index.chars(), entry.source(), screenPos);
            Visual visual = new Visual(text, index, drawWPx, entryScale, textTop, color, frameColorBase,
                entry.source() == DanmakuSource.ROOM_CHAT
                    ? inkBounds(font, text, textTop, color, true) : null);
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
        drawBand(g, font, actives, DanmakuMode.SCROLL, clocks, px, py, pw, lanes);
        drawBand(g, font, actives, DanmakuMode.REVERSE, clocks, px, py, pw, lanes);
        drawBand(g, font, actives, DanmakuMode.TOP, clocks, px, py, pw, lanes);
        drawBand(g, font, actives, DanmakuMode.BOTTOM, clocks, px, py, pw, lanes);
    }

    /**
     * 单条带的提交：滚动/逆向条目按连续落点提交（落点自显示带上缘起找位），
     * TOP 自顶部向下、BOTTOM 自底部向上（槽位 0 最靠下）。
     */
    private static void drawBand(GuiGraphicsExtractor g, Font font, List<Active> actives, DanmakuMode band,
                                 Clocks clocks, int px, int py, int pw, LaneGeometry lanes) {
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
            // 主字与描边一次提交：描边色由条目文本自带的投影样式决定（见类注释），
            // 故描边 alpha 与主字同源（danmakuOpacity），条目在屏文本绘制调用恒为 1 次
            g.text(font, seq, 0, textTopPx, visual.color(), true);
            g.pose().popMatrix();
        }
    }

    /**
     * 空心文字框：画面像素域四条 {@link #FRAME_EDGE} 细边，框内不填充，随条目落点/位移同步。
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

    /**
     * 条目落点的纵向位置（画面像素域，显示区坐标）：滚动/逆向条目用准入时定下的连续落点，
     * 固定项自显示区顶部往下（BOTTOM 自底部往上、槽位 0 最靠下）。
     */
    private static float posY(Active active, LaneGeometry lanes) {
        return switch (active.mode()) {
            case TOP -> lanes.topOf(active.slot());
            case BOTTOM -> lanes.bottomOf(active.slot());
            default -> active.top();
        };
    }

    /**
     * 在屏条目纵向坐标收口（每帧按当前几何做，无变化即无操作）：滚动/逆向落点收进
     * [0, 可用下界 − 条目高]，固定项槽位收进 [0, 车道数−1]。
     *
     * @return 实际被移动的条目数（仅变化时才新建记录）
     */
    private static int refitActives(List<Active> actives, LaneGeometry lanes) {
        int moved = 0;
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() == DanmakuMode.SCROLL || active.mode() == DanmakuMode.REVERSE) {
                float top = DanmakuPack.refitTop(active.top(), active.height(), lanes.bottomLimit);
                if (top == active.top()) continue;
                actives.set(i, active.withTop(top));
            } else {
                int slot = DanmakuWorldLayer.clampLane(active.slot(), lanes.lanes);
                if (slot == active.slot()) continue;
                actives.set(i, active.withSlot(slot));
            }
            moved++;
        }
        return moved;
    }

    /** 条目整条移出显示区（滚动/逆向滚动的出场态），固定项居中恒在显示区内 */
    private static boolean isOutside(float x, float drawWPx, int pw) {
        return x > pw || x + drawWPx < 0;
    }

    /**
     * 单条准入：固定项占用感知地分配槽位，滚动/逆向条目交给 {@link DanmakuPack} 求连续纵向落点。
     *
     * <p>社交条目永不丢弃：抢占腾位时会从 actives 就地移除被抢占条目，仍无落点则被迫纵向压叠入屏。
     *
     * @param visual       准入时算好的绘制参数（文本/宽度/缩放/配色/文字框外接框）
     * @param lanes        本帧车道几何（车道高与车道数随字号缩放，可用下界随底部控制条安全区）
     * @param allowOverlap danmakuAllowOverlap：开启后视频片内条目无落点时压叠上屏而非丢弃
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
                // 占位宽度与文本框高都按条目实际缩放折算（与世界层同一口径）
                float height = DanmakuWorldLayer.GLYPH_HEIGHT_PX * visual.scale();
                DanmakuPack.Pick pick = PACK.place(actives, mode, entry.source(), visual.textWPx(),
                    height, visual.scale(), pw, lanes.bottomLimit, travelMs, clocks.monoMs(),
                    clocks.videoMs(), allowOverlap);
                if (pick.top() < 0.0f) yield Admission.DROPPED;
                Active victim = pick.evictIndex() >= 0 ? actives.remove(pick.evictIndex()) : null;
                yield new Admission(new Active(entry, visual, clocks.of(entry.source()), mode,
                    (int) travelMs, -1, pick.top(), height), victim, pick.forced());
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
        return new Admission(new Active(entry, visual, startMs, pinMode, 0, pick.slot(), 0.0f, 0.0f),
            null, pick.forced());
    }

    /**
     * 固定槽位占用情况（长度 = 本帧槽位数）：被在途条目占用的槽位不可复用；
     * 越界槽位号（几何缩水后尚未收口）不计入占用，与 {@link DanmakuWorldLayer#clampLane} 同口径。
     */
    private static boolean[] pinUsage(List<Active> actives, DanmakuMode mode, int pinSlots) {
        boolean[] used = new boolean[pinSlots];
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() == mode && active.slot() >= 0 && active.slot() < pinSlots) {
                used[active.slot()] = true;
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

    /**
     * 文本：房间互发按「昵称：内容」组装（senderName 为 null 的 B 站来源不加前缀）；
     * 描边开启时挂 {@link #OUTLINE_SHADOW_STYLE} 投影色（原版字形投影通道，见类注释）。
     */
    private static FormattedCharSequence entryText(DanmakuEntry entry, boolean outline) {
        MutableComponent text = entry.source() == DanmakuSource.ROOM_CHAT
                && entry.senderName() != null && !entry.senderName().isEmpty()
            ? Component.literal(entry.senderName() + "：" + entry.text())
            : Component.literal(entry.text());
        return (outline ? text.withStyle(OUTLINE_SHADOW_STYLE) : text).getVisualOrderText();
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
