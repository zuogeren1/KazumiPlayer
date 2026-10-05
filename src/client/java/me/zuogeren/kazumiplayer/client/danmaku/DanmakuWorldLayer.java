package me.zuogeren.kazumiplayer.client.danmaku;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderState;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界内弹幕文字层（挂在 VideoScreenRenderer.submit 尾部，屏幕面局部坐标系内绘制）。
 *
 * <p>每帧从 {@link ClientDanmakuStore#pollDue} 消费该屏到期条目。三条显示带各自独立，
 * 互不改变对方的几何：滚动带起点恒为显示区顶部、TOP 带自顶部向下、BOTTOM 带自底部向上，
 * 三条带重叠时按「滚动 → TOP → BOTTOM」次序提交，固定项自然压在滚动项之上（B 站语义）。
 *
 * <p>时钟：条目按来源选驱动时钟——片内弹幕（BILIBILI_VIDEO）用 {@link VideoScreenRenderState}
 * 携带的播放器位置（{@code state.player.getTimeMs()}，暂停期为冻结值），暂停即冻结、恢复后从
 * 停住处继续；房间互发与直播弹幕用本地单调钟，不受暂停影响。行程进度、驻留时长与车道让位判据
 * 的推进量都取该条自身的时钟。
 *
 * <p>车道准入是占用感知的：该车道每条在途弹幕的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) +
 * {@link #MIN_GAP_PX} 才允许新条入轨（按行程比例换算成时间提前量），占位宽度取两者的较大值，
 * 保证更宽的追随弹幕（行程更快）在最坏时刻也不会追上前车。TOP/BOTTOM 槽位同样按驻留时长占位。
 *
 * <p>社交优先：出队后按来源优先级（ROOM_CHAT &gt; BILIBILI_LIVE &gt; BILIBILI_VIDEO）稳定排序再准入；
 * {@code config.danmakuScreenCap()} 容量闸只拦视频片内条目，社交条目不受上限约束（仍计入在屏数）；
 * 社交条目优先走空闲车道，其次抢占该车道里最接近离场的视频片内条目腾位，仍不足时放进「最空」
 * 的车道并允许与在途条目重叠（该条豁免 MIN_GAP 不变量，计入 forced-overlap DEBUG 计数）——
 * 即社交条目永不丢弃；视频片内条目按原规则丢弃。
 *
 * <p>模式：SCROLL 右进左出、REVERSE 左进右出（镜像）；TOP/BOTTOM 居中驻留 {@link #PIN_HOLD_MS}，
 * 不受 speedMultiplier 影响。
 *
 * <p>开关：来源三开关与模式三开关过滤出队条目；danmakuShowColored=false 时跳过颜色非白的
 * B 站条目（房间互发恒金字不受影响）；danmakuShowAdvanced=true 时把高级弹幕（B 站 mode 7/8/9）
 * 按其文本按时间降级为普通滚动，降级后与普通条目同样参与车道准入，false 则跳过。
 *
 * <p>来源差异：房间互发（{@link DanmakuSource#ROOM_CHAT}）按「昵称：内容」组装并染
 * {@link #ROOM_CHAT_COLOR} 金色、外描一个空心矩形文字框；B 站弹幕按包内自身颜色渲染、不画框。
 * 文字框矩形由字形实际绘制外接框（{@code Font#prepareText} 的 bounds）四边各外扩 {@link #FRAME_PAD}
 * 像素推导，文字在框内水平与垂直都居中。文字框与描边的 alpha 一律由 danmakuOpacity 控制。
 *
 * <p>字号→世界换算：车道几何随 danmakuFontScale 缩放——车道高 laneHPx = 9（字形行高）× 1.4（行距系数）
 * × danmakuFontScale，车道数 = clamp(floor(显示区基准高 / laneHPx), 1, {@link #MAX_LANE_COUNT})，
 * 故字号缩小即行距收紧、车道变多且铺满显示区；
 * 字号百分比只作用于条目自身的 pose 缩放 q_i = danmakuFontScale × fontSizePercent/100，故任一条目上下场
 * 都不会改变他人的位置与大小；q_i 会被钳制到单条车道高以内，避免超高条目溢出到相邻车道。基准换算因子
 * {@link #baseScale} 按 danmakuScaleWithScreen 取屏幕实际尺寸或固定基准半高——关闭缩放时字号
 * 在世界中恒为默认屏幕（3×2 格）折算的大小，不随屏幕方块尺寸变化（全屏 HUD 层不受该开关影响）。
 *
 * <p>显示区坐标：原点为屏幕面中心，显示带高恒为 danmakuAreaRatio 指定的居中显示区（像素域基准
 * {@link #BASE_BAND_H_PX}，不随字号变化）、宽 = 屏幕面宽，x 向为 SCROLL 的起跑侧、y 向为下缘侧。
 * 车道自显示区上/下缘按 laneHPx 依次铺开，故车道区间恒在显示区内且互不重叠。条目位置一律按实际绘制宽度（textWPx × q）计算，
 * 位移不随字号缩放；字形按 (laneHPx - 9q)/2 在车道内垂直居中，故缩放后仍不越出本车道；
 * 整条移出显示区（按绘制外接框与显示区求交，含纵向）即不提交，文字不会飘到屏幕面之外。
 *
 * <p>描边走 {@code SubmitNodeCollector.submitText} 的 outlineColor 参数（26.1.2 mojmap
 * SubmitNodeStorage L58-L72 十参签名，末位 outlineColor）；文字框走
 * {@code submitCustomGeometry} + 1x1 白纹理的四条细边（同 VideoScreenRenderer 的 fillBar 先例）。
 *
 * <p>逐帧成本：视觉序文本、基础宽、pose 缩放、主色/描边色/框色与字形外接框都在准入时一次算好
 * （见 {@link Visual}），绘制循环只做偏移算术、不重排文本也不再合成颜色，且不新建对象
 * （原 {@code Placement} 中间记录改成两个标量落点方法；文字框四条边合并为一次
 * {@code submitCustomGeometry}——该捕获式 lambda 是延迟提交管线的固有分配，仅房间互发条目每帧一次）。
 *
 * <p>场景态清理（f10 裁定 A 的渲染侧责任）：Store 内部只压缩过期条目，跳变后的活动弹幕由本层
 * 按「上帧视频时间」自检清理——|Δ| > {@link ClientDanmakuStore#SEEK_DETECT_MS} 时清空该屏 ACTIVE，
 * 避免 seek 后旧弹幕继续飘完行程。
 */
public final class DanmakuWorldLayer {

    /** 固定模式（顶部/底部）各自可用的居中槽位上限（实际槽位数再被车道数收口，不越出显示区） */
    private static final int PIN_SLOT_COUNT = 3;
    /** 字形行高像素（原版字体 9px）与行距系数：车道高 = 9 × 1.4 × danmakuFontScale */
    private static final float GLYPH_HEIGHT_PX = 9.0f;
    private static final float LINE_HEIGHT_FACTOR = 1.4f;
    /** 车道数上限：小字号时防显示区内车道过多（48 条足以在常见窗口下铺满显示区） */
    private static final int MAX_LANE_COUNT = 48;
    /** 100% 字号下的基准车道数：danmakuAreaRatio 所指定显示区的像素域基准高由此定义 */
    private static final int BASE_LANE_COUNT = 5;
    /** 显示区像素域基准高 = 基准车道数 × 基准车道高（9×1.4）：显示区本身不随字号变化 */
    private static final float BASE_BAND_H_PX = BASE_LANE_COUNT * GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR;
    /** 顶部/底部项驻留时长（毫秒）：固定模式不受 speedMultiplier 影响 */
    private static final long PIN_HOLD_MS = 4500L;
    /** 车道让位判据的水平间隔（像素）：旧条已推进像素须 ≥ max(新条,旧条)文本宽 + 此值才释放车道 */
    private static final float MIN_GAP_PX = 6.0f;
    /** danmakuScaleWithScreen=false 时的固定世界字号基准：默认屏幕尺寸（3×2 格）的半高 */
    private static final float FIXED_SCALE_BASIS_HALF_H = 1.0f;
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 白色弹幕过滤基准色（danmakuShowColored=false 时保留的 B 站条目颜色） */
    private static final int WHITE_RGB = 0xFFFFFF;
    /** 房间互发弹幕文字框内边距（显示区像素，四边相等）与线宽（显示区像素） */
    private static final float FRAME_PAD = 2.0f;
    private static final float FRAME_EDGE = 1.0f;
    /** 文字框深度：略在文字之后（文字用 POLYGON_OFFSET 前移），保证框在文字下层 */
    private static final float FRAME_Z = -0.01f;
    /** 文字框实色矩形纹理（运行期注册的 1x1 纯白，专用 id 不与进度条纹理重名） */
    private static final Identifier FRAME_TEX =
        Identifier.fromNamespaceAndPath("kazumiplayer", "danmaku_frame_white");
    private static boolean frameTexRegistered;
    /** 文字框 RenderType 缓存（懒构造，避免逐帧重建） */
    private static RenderType frameRenderType;

    /**
     * 车道几何（纯算术，两层共用同一口径）：车道高 = 字形行高 9 × 行距系数 1.4 × danmakuFontScale，
     * 车道数由显示区高推导 lanes = clamp(floor(显示区高 / 车道高), 1, {@link #MAX_LANE_COUNT})——
     * 字号越小行距越紧、车道越多（上限 {@link #MAX_LANE_COUNT}，足以铺满显示区）。车道自显示区顶/底缘
     * 按车道高依次铺开，故任意车道区间都落在显示区内且互不重叠；车道高以显示区高为上限收口，
     * 显示区比单条车道还矮时只剩一条车道，区间仍不越界。
     * {@link DanmakuHudLayer} 以等比画面像素高为显示区高调用同一实现。
     */
    static final class LaneGeometry {

        /** 显示区高（像素域）：HUD 层=等比画面像素高，世界层=显示区基准高 */
        final float areaH;
        /** 单条车道高 = 9 × 1.4 × danmakuFontScale（不超过显示区高） */
        final float laneH;
        /** 车道数 = clamp(floor(显示区高 / laneH), 1, {@link #MAX_LANE_COUNT}) */
        final int lanes;

        private LaneGeometry(float areaH, float laneH, int lanes) {
            this.areaH = areaH;
            this.laneH = laneH;
            this.lanes = lanes;
        }

        /**
         * @param areaH     显示区高（HUD 层=等比画面像素高；世界层={@link #BASE_BAND_H_PX}）
         * @param fontScale danmakuFontScale
         */
        static LaneGeometry of(float areaH, float fontScale) {
            float laneH = Math.min(GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR * fontScale, areaH);
            // 浮点误差兜底：显示区高恰为整数倍车道高时（如 63 / 12.6）仍判为整除
            int lanes = Math.max(1, Math.min(MAX_LANE_COUNT,
                (int) Math.floor(areaH / (double) laneH + 1.0e-5)));
            return new LaneGeometry(areaH, laneH, lanes);
        }

        /** 车道自显示区顶部起的上缘（滚动带/顶部带）；底部带用 {@link #bottomOf(int)} */
        float topOf(int lane) {
            return lane * laneH;
        }

        /** 车道自显示区底部起的上缘（底部带，槽位 0 最靠下） */
        float bottomOf(int lane) {
            return areaH - (lane + 1) * laneH;
        }

        /** 单条条目的缩放上限：超出即压到相邻车道（= 车道高 / 字形行高） */
        float maxEntryScale() {
            return laneH / GLYPH_HEIGHT_PX;
        }

        /** 固定槽位数：不超过车道数，保证固定项仍落在显示区内 */
        int pinSlots() {
            return Math.min(PIN_SLOT_COUNT, lanes);
        }
    }

    /**
     * 异常日志聚合器（纯状态机，两层共用）：只有出现异常计数的帧才有输出——窗口内首次立即打印本帧明细，
     * 其后同一屏每 {@link #WINDOW_MS} 毫秒至多再打印一条累计行；全部帧都无异常时不产出任何日志
     * （逐帧 consumed 行会刷屏，无法查看其它信息）。
     */
    static final class AnomalyLog {

        /** 持续异常时的聚合打印间隔（毫秒） */
        static final long WINDOW_MS = 5000L;

        private boolean reported;
        private long lastEmitMs;
        private int frames;
        private int due;
        private int admitted;
        private int dropped;
        private int noLane;
        private int capped;
        private int evicted;
        private int forced;
        private int skipped;

        /** 本帧是否出现异常计数（无异常即完全静默） */
        static boolean isAnomaly(int noLane, int capped, int evicted, int forced, int skipped) {
            return noLane > 0 || capped > 0 || evicted > 0 || forced > 0 || skipped > 0;
        }

        /**
         * 投入一帧计数。
         *
         * @return null=静默（本帧无异常，或异常仍在聚合窗口内）；否则为应打印的内容
         */
        Report submit(long nowMs, int dueCount, int admittedCount, int noLaneCount, int cappedCount,
                      int evictedCount, int forcedCount, int skippedCount) {
            if (!isAnomaly(noLaneCount, cappedCount, evictedCount, forcedCount, skippedCount)) return null;
            frames++;
            due += dueCount;
            admitted += admittedCount;
            dropped += noLaneCount + cappedCount + skippedCount;
            noLane += noLaneCount;
            capped += cappedCount;
            evicted += evictedCount;
            forced += forcedCount;
            skipped += skippedCount;
            if (reported && nowMs - lastEmitMs < WINDOW_MS) return null;
            Report report = new Report(frames > 1, frames, due, admitted, dropped, noLane, capped,
                evicted, forced, skipped);
            reported = true;
            lastEmitMs = nowMs;
            frames = 0;
            due = 0;
            admitted = 0;
            dropped = 0;
            noLane = 0;
            capped = 0;
            evicted = 0;
            forced = 0;
            skipped = 0;
            return report;
        }

        /**
         * 应打印的异常日志内容。
         *
         * @param aggregate true=窗口累计行（frames&gt;1，标明为聚合）；false=首个异常帧的明细行
         */
        record Report(boolean aggregate, int frames, int due, int admitted, int dropped, int noLane,
                      int capped, int evicted, int forced, int skipped) {}
    }

    /** 异常日志行的统一格式（两层同口径）：aggregate 标记窗口累计行 */
    static void logAnomalies(String layer, BlockPos pos, AnomalyLog.Report report, int activeCount) {
        KazumiLog.danmaku.debug(
            "{} danmaku anomalies{} at {} (frames {}, due {}, admitted {}, dropped {}, no-free-lane {}, "
                + "capped-video {}, evicted-for-social {}, forced-overlap {}, advanced-skipped {}, active {})",
            layer, report.aggregate() ? " aggregated" : "", pos, report.frames(), report.due(),
            report.admitted(), report.dropped(), report.noLane(), report.capped(), report.evicted(),
            report.forced(), report.skipped(), activeCount);
    }

    /**
     * 准入时一次算好的绘制参数（绘制循环逐帧只读，零重算、零分配）。
     *
     * @param text         视觉序文本（入场时 {@code getVisualOrderText()} 一次，此后不再重排）
     * @param textWPx      条目文本基础像素宽（未乘 {@code scalePercent}）
     * @param scalePercent 条目 pose 缩放（danmakuFontScale × fontSizePercent/100，钳制到车道高以内）
     * @param color        主色（已按 danmakuOpacity 合成 alpha；房间互发恒金）
     * @param outlineColor 描边色（已合成 alpha，未开启描边时不被使用）
     * @param frameColor   文字框色（已合成 alpha）
     * @param frameInk     房间互发弹幕的字形外接框（{@code prepareText} 一次，以 y=0 为基准），其余来源为 null
     */
    private record Visual(FormattedCharSequence text, float textWPx, float scalePercent, int color,
                          int outlineColor, int frameColor, ScreenRectangle frameInk) {}

    /**
     * 在屏条目。
     *
     * @param startMs 该条驱动时钟在准入时刻的取值（社交=单调钟，片内=播放器位置）
     */
    private record Active(DanmakuEntry entry, Visual visual, long startMs, long seq, DanmakuMode mode,
                          int travelMs, int lane) {}

    /** 帧时钟：社交弹幕（房间/直播）用单调钟，片内弹幕用播放器位置——暂停期后者冻结 */
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
     * @param forced  社交条目被迫重叠入轨（无空闲车道/槽位且无可腾位视频）
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
    private static long seqCursor;
    /** 滚动车道轮转游标：多条同时可入时用于分散到不同车道 */
    private static int laneCursor;
    private static boolean clearHookInstalled;

    private DanmakuWorldLayer() {}

    /**
     * 渲染帧入口：消费到期条目并绘制活动弹幕。
     * 调用方保证已处于屏幕面局部坐标（translate+rotateToFacing 之后、popPose 之前）。
     */
    public static void draw(SubmitNodeCollector collector, PoseStack poseStack,
                            VideoScreenRenderState state, float halfW, float halfH) {
        var config = ClientConfig.CONFIG;
        if (!config.danmakuEnabled.get()) return;
        // 防双消费：全屏激活期由 DanmakuHudLayer 独占 pollDue 出队（coverage<100 时世界画面短暂无弹幕为既定取舍）
        if (me.zuogeren.kazumiplayer.client.ClientFullscreenState.isActive()) return;
        installClearHook();

        BlockPos pos = state.blockPos;
        long travelMs = scrollTravelMs();
        long videoTimeMs = state.player != null ? state.player.getTimeMs() : 0;
        Clocks clocks = new Clocks(MonoClock.millis(), videoTimeMs);

        // 场景态清理先于出队：跳变帧的旧弹幕整批作废
        if (clearOnSeek(pos, videoTimeMs)) {
            KazumiLog.danmaku.debug("World layer cleared active danmaku at {} after seek to {}ms",
                pos, videoTimeMs);
        }

        List<DanmakuEntry> due = ClientDanmakuStore.pollDue(pos, videoTimeMs);
        // 社交优先占位：同一帧内按来源优先级稳定排序后再准入（同优先级保持出队序）
        if (due.size() > 1) {
            due.sort((a, b) -> Integer.compare(sourcePriority(a.source()), sourcePriority(b.source())));
        }
        List<Active> actives = ACTIVE.computeIfAbsent(pos, k -> new ArrayList<>());

        // 车道几何随字号缩放：车道高 = 9×1.4×danmakuFontScale，车道数由显示区高推导（1..12）
        float baseScale = baseScale(halfH, config);
        float halfWPx = halfW / baseScale;
        float fontScale = config.danmakuFontScale.get().floatValue();
        LaneGeometry lanes = LaneGeometry.of(BASE_BAND_H_PX, fontScale);
        Font font = Minecraft.getInstance().font;
        // 颜色与文字框几何都在准入时一次算好（每帧只读缓存值，不再逐帧合成 ARGB）
        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int alpha = Math.max(0, Math.min(255, Math.round(alphaFactor * 255.0f)));
        int outlineColorBase = ARGB.black(alpha);
        int frameColorBase = ARGB.color(alpha, ROOM_CHAT_COLOR);

        int accepted = 0;
        int noLane = 0;
        int capped = 0;
        int evicted = 0;
        int forced = 0;
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
            // 单条占用高度按实际缩放算并钳制到车道高以内，避免超大字号溢出压到相邻车道
            float entryScale = Math.min(fontScale * scalePercent, lanes.maxEntryScale());
            float textWPx = font.width(text);
            int color = entryColor(entry, alphaFactor);
            Visual visual = new Visual(text, textWPx, entryScale, color, outlineColorBase,
                frameColorBase, entry.source() == DanmakuSource.ROOM_CHAT
                    ? inkBounds(font, text, 0.0f, color, !outline) : null);
            Admission adm = admit(entry, mode, visual, actives, clocks, travelMs, halfWPx, lanes);
            if (adm.active() == null) {
                noLane++;
                continue;
            }
            if (adm.evicted() != null) evicted++;
            if (adm.forced()) forced++;
            actives.add(adm.active());
            accepted++;
        }
        // 每帧异常才输出：首个异常帧一条明细，其后每 5 秒至多一条累计行；无异常帧完全静默
        AnomalyLog.Report report = ANOMALY.computeIfAbsent(pos, k -> new AnomalyLog())
            .submit(clocks.monoMs(), due.size(), accepted, noLane, capped, evicted, forced, skipped);
        if (report != null) logAnomalies("World layer", pos, report, actives.size());

        for (int i = actives.size() - 1; i >= 0; i--) {
            Active active = actives.get(i);
            if (clocks.of(active.entry().source()) - active.startMs() >= lifetimeMs(active)) {
                actives.remove(i);
            }
        }
        if (actives.isEmpty()) {
            ACTIVE.remove(pos);
            return;
        }

        poseStack.pushPose();
        poseStack.scale(baseScale, baseScale, baseScale);
        // 屏幕面局部坐标系相对观察者为 XY 双反（视频 quad 以 U/V 双翻转补偿，见 VideoScreenRenderer UV 注释），
        // 文字层叠加同轴 Z180 真旋转对消——修正水平反向与上下倒置；det=+1 不触发背面剔除
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));

        // 分带提交：滚动带先画，TOP/BOTTOM 后画 → 三条带重叠处固定项压在上层
        drawBand(collector, poseStack, actives, DanmakuMode.SCROLL, clocks, halfWPx, lanes, outline);
        drawBand(collector, poseStack, actives, DanmakuMode.REVERSE, clocks, halfWPx, lanes, outline);
        drawBand(collector, poseStack, actives, DanmakuMode.TOP, clocks, halfWPx, lanes, outline);
        drawBand(collector, poseStack, actives, DanmakuMode.BOTTOM, clocks, halfWPx, lanes, outline);
        poseStack.popPose();
    }

    /**
     * 像素域→世界域的基准缩放：显示区高（世界单位）= 屏幕面高 × danmakuAreaRatio，除以显示区
     * 像素域基准高 {@link #BASE_BAND_H_PX}（= 基准车道数 × 基准车道高 9×1.4），故字号缩放只改变
     * 车道高与车道数、不改变显示区大小。danmakuScaleWithScreen=false 时改用固定基准半高
     * {@link #FIXED_SCALE_BASIS_HALF_H}（默认屏幕尺寸），字号不再随屏幕方块尺寸缩放。
     *
     * @param halfH 屏幕面半高（世界单位/格）
     */
    static float baseScale(float halfH, ClientConfig config) {
        float basisHalfH = config.danmakuScaleWithScreen.get() ? halfH : FIXED_SCALE_BASIS_HALF_H;
        float bandWorldH = 2 * basisHalfH * config.danmakuAreaRatio.get().floatValue();
        return bandWorldH / BASE_BAND_H_PX;
    }

    /** 单条带的提交：滚动带起点恒为显示区顶部；TOP 自顶部向下、BOTTOM 自底部向上（slot 0 最靠下） */
    private static void drawBand(SubmitNodeCollector collector, PoseStack poseStack, List<Active> actives,
                                 DanmakuMode band, Clocks clocks, float halfWPx, LaneGeometry lanes,
                                 boolean outline) {
        for (int i = 0; i < actives.size(); i++) {
            Active active = actives.get(i);
            if (active.mode() != band) continue;
            Visual visual = active.visual();
            float q = visual.scalePercent();
            float drawnWPx = visual.textWPx() * q;
            float x = placementX(active, clocks, halfWPx, drawnWPx);
            float yTop = placementYTop(active, lanes);
            // 整条移出显示区即不再提交（按实际绘制外接框求交，避免文字飘到屏幕面之外）
            if (outsideDisplay(x, yTop, drawnWPx, lanes.laneH, halfWPx)) continue;
            // 字形在车道内垂直居中：字号缩放后仍不越出本车道与显示区上下缘
            float localTextTop = textTopInBand(q, lanes.laneH) / q;

            poseStack.pushPose();
            poseStack.translate(x, yTop, 0.0f);
            poseStack.scale(q, q, 1.0f);
            ScreenRectangle ink = visual.frameInk();
            if (ink != null) {
                // 缓存外接框以 y=0 为基准：绘制期补上条目垂直偏移，内边距除以 q 后屏幕上恒为 FRAME_PAD
                float pad = FRAME_PAD / q;
                submitFrame(collector, poseStack, ink.left() - pad, ink.right() + pad,
                    ink.top() + localTextTop - pad, ink.bottom() + localTextTop + pad,
                    FRAME_EDGE / q, visual.frameColor());
            }
            collector.submitText(poseStack, 0.0f, localTextTop, visual.text(),
                !outline, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, visual.color(), 0,
                outline ? visual.outlineColor() : 0);
            poseStack.popPose();
        }
    }

    /** 显示带半高（像素）：danmakuAreaRatio 指定的显示区居中于屏幕面，恒为 {@link #BASE_BAND_H_PX}/2，不随字号变化 */
    private static float halfBand() {
        return BASE_BAND_H_PX / 2.0f;
    }

    /** 条目落点横坐标（屏幕面局部像素坐标：原点为面中心，x 向为 SCROLL 起跑侧） */
    private static float placementX(Active active, Clocks clocks, float halfWPx, float drawnWPx) {
        // 横向一律按实际绘制宽度（textWPx × q）计算，位移不随字号缩放放大：
        // SCROLL 自 +x 缘进入向左行进、REVERSE 自 -x 缘进入向右行进（互为镜像），固定项居中
        if (active.mode() == DanmakuMode.TOP || active.mode() == DanmakuMode.BOTTOM) {
            return -drawnWPx / 2.0f;
        }
        float travel = 2 * halfWPx + drawnWPx;
        float progress = progress(active, clocks);
        return active.mode() == DanmakuMode.REVERSE
            ? -halfWPx - drawnWPx + progress * travel
            : halfWPx - progress * travel;
    }

    /** 条目落点纵坐标（y 向为下缘侧）：车道自显示区上/下缘按车道高铺开，底部带槽位 0 最靠下 */
    private static float placementYTop(Active active, LaneGeometry lanes) {
        return -halfBand() + (active.mode() == DanmakuMode.BOTTOM
            ? lanes.bottomOf(active.lane()) : lanes.topOf(active.lane()));
    }

    /** 行程进度 p∈[0,1]：按条目自身时钟推进（片内弹幕暂停即冻结；固定项 travelMs=0 不参与行程） */
    private static float progress(Active active, Clocks clocks) {
        int travelMs = active.travelMs();
        if (travelMs <= 0) return 0.0f;
        return (clocks.of(active.entry().source()) - active.startMs()) / (float) travelMs;
    }

    /** 字形在车道内的垂直偏移（显示区像素）：缩放后仍居中于本车道 */
    private static float textTopInBand(float q, float laneH) {
        return (laneH - GLYPH_HEIGHT_PX * q) / 2.0f;
    }

    /** 整条落在显示区之外（横向按屏幕面宽度、纵向按显示带）即不提交：按绘制外接框求交 */
    private static boolean outsideDisplay(float x, float yTop, float drawnWPx, float laneH, float halfWPx) {
        float half = halfBand();
        return x >= halfWPx || x + drawnWPx <= -halfWPx
            || yTop >= half || yTop + laneH <= -half;
    }

    /** 文字实际绘制外接框（条目局部域，以 y=0 为基准）：取字形四边形真实边界，空文本返回 null */
    private static ScreenRectangle inkBounds(Font font, FormattedCharSequence text, float localTextTop,
                                             int color, boolean dropShadow) {
        return font.prepareText(text, 0.0f, localTextTop, color, dropShadow, false, 0).bounds();
    }

    /** 跳变帧清理：|Δ| 超过 Store 的 seek 阈值即清空该屏活动弹幕（首帧只记基线） */
    public static boolean clearOnSeek(BlockPos pos, long currentVideoTimeMs) {
        Long previous = LAST_VIDEO_TIME.put(pos, currentVideoTimeMs);
        if (previous == null) return false;
        if (Math.abs(currentVideoTimeMs - previous) <= ClientDanmakuStore.SEEK_DETECT_MS) return false;
        return ACTIVE.remove(pos) != null;
    }

    /**
     * 单条准入：占用感知地分配车道/槽位。
     *
     * <p>社交条目永不丢弃：抢占腾位时会从 actives 就地移除被抢占条目，仍无空位则被迫重叠入轨。
     *
     * @param visual 准入时算好的绘制参数（文本/基础宽度/缩放/配色/文字框外接框）
     * @param lanes  本帧车道几何（车道高与车道数随字号缩放）
     * @return 准入结果；active 为 null 表示丢弃该条
     */
    private static Admission admit(DanmakuEntry entry, DanmakuMode mode, Visual visual,
                                   List<Active> actives, Clocks clocks, long travelMs, float halfWPx,
                                   LaneGeometry lanes) {
        long seq = seqCursor++;
        long startMs = clocks.of(entry.source());
        int pinSlots = lanes.pinSlots();
        return switch (mode) {
            case TOP -> pinAdmit(entry, visual, actives, clocks, DanmakuMode.TOP, seq, startMs, pinSlots);
            case BOTTOM -> pinAdmit(entry, visual, actives, clocks, DanmakuMode.BOTTOM, seq, startMs, pinSlots);
            case SCROLL, REVERSE -> {
                // 占位宽度按条目实际缩放后的绘制宽度计（与 HUD 侧同判据）
                LanePick pick = pickLane(actives, mode, visual.textWPx() * visual.scalePercent(), halfWPx,
                    clocks, travelMs, !isVideoDanmaku(entry.source()), lanes.lanes);
                if (pick.lane() < 0) yield Admission.DROPPED;
                Active victim = pick.evictIndex() >= 0 ? actives.remove(pick.evictIndex()) : null;
                yield new Admission(new Active(entry, visual, startMs, seq, mode, (int) travelMs,
                    pick.lane()), victim, pick.forced());
            }
            case ADVANCED -> Admission.DROPPED;
        };
    }

    /** 固定项准入：优先空槽；社交条目无空槽时占「最接近释放」的槽位（被迫重叠并计入 forced） */
    private static Admission pinAdmit(DanmakuEntry entry, Visual visual, List<Active> actives,
                                      Clocks clocks, DanmakuMode pinMode, long seq, long startMs,
                                      int pinSlots) {
        int slot = firstFreePin(actives, pinMode, pinSlots);
        boolean forced = false;
        if (slot < 0) {
            if (isVideoDanmaku(entry.source())) return Admission.DROPPED;
            slot = oldestPin(actives, pinMode, clocks, pinSlots);
            if (slot < 0) return Admission.DROPPED;
            forced = true;
        }
        return new Admission(new Active(entry, visual, startMs, seq, pinMode, 0, slot), null, forced);
    }

    /** 固定槽位占用感知：被在途条目占用的槽位不可复用；返回可用槽位下标，无则 -1 */
    private static int firstFreePin(List<Active> actives, DanmakuMode mode, int pinSlots) {
        boolean[] used = new boolean[pinSlots];
        for (Active active : actives) {
            if (active.mode() == mode && active.lane() < pinSlots) {
                used[active.lane()] = true;
            }
        }
        for (int i = 0; i < pinSlots; i++) {
            if (!used[i]) return i;
        }
        return -1;
    }

    /** 「最接近释放」的固定槽位：占用者中按各自时钟已驻留最久者；无占用返回 -1 */
    private static int oldestPin(List<Active> actives, DanmakuMode mode, Clocks clocks, int pinSlots) {
        int slot = -1;
        long bestElapsed = Long.MIN_VALUE;
        for (Active active : actives) {
            if (active.mode() != mode || active.lane() >= pinSlots) continue;
            long elapsed = clocks.of(active.entry().source()) - active.startMs();
            if (elapsed > bestElapsed) {
                bestElapsed = elapsed;
                slot = active.lane();
            }
        }
        return slot;
    }

    /**
     * 车道分配三遍：1) 按轮转游标找已让出空间的车道；2) 社交条目按同一轮转序试算抢占——移除该车道
     * 里行程 progress 最大的视频片内条目后确实让出空间才腾位（不成立不白丢视频条目）；
     * 3) 社交条目仍无位时放进「最空」的车道（该车道最后一条已推进像素最多者，重叠量最小）并标记被迫重叠。
     * 视频片内条目只走第一遍，无位即丢弃。
     *
     * @param social    该条是否为社交条目（房间互发/直播）：可抢占、可被迫重叠
     * @param laneCount 本帧车道数（随字号缩放）
     */
    private static LanePick pickLane(List<Active> actives, DanmakuMode mode, float newWPx, float halfWPx,
                                     Clocks clocks, long travelMs, boolean social, int laneCount) {
        for (int offset = 0; offset < laneCount; offset++) {
            int lane = Math.floorMod(laneCursor + offset, laneCount);
            if (laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs)) {
                laneCursor = Math.floorMod(lane + 1, laneCount);
                return new LanePick(lane, -1, false);
            }
        }
        if (!social) return new LanePick(-1, -1, false);
        for (int offset = 0; offset < laneCount; offset++) {
            int lane = Math.floorMod(laneCursor + offset, laneCount);
            int victim = evictCandidate(actives, mode, lane, clocks);
            if (victim < 0) continue;
            if (!laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs, victim)) continue;
            laneCursor = Math.floorMod(lane + 1, laneCount);
            return new LanePick(lane, victim, false);
        }
        int bestLane = -1;
        float mostAdvance = -1.0f;
        for (int lane = 0; lane < laneCount; lane++) {
            float advance = lastAdvance(actives, mode, lane, halfWPx, clocks, travelMs);
            if (advance > mostAdvance) {
                mostAdvance = advance;
                bestLane = lane;
            }
        }
        if (bestLane < 0) return new LanePick(-1, -1, false);
        laneCursor = Math.floorMod(bestLane + 1, laneCount);
        return new LanePick(bestLane, -1, true);
    }

    /**
     * 车道准入判据：该车道每条在途弹幕的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) + {@link #MIN_GAP_PX}。
     * 宽度一律按条目实际缩放后的绘制宽度计（textWPx × scalePercent），与 HUD 侧同一口径；
     * 占位宽度取两者较大值——更宽的弹幕行程更快会追上较窄的前车，只用前车宽度算提前量会在最坏时刻追尾；
     * 推进量按该条自身时钟计（片内弹幕暂停期不推进，不会被误判为已让位）。
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        float halfWPx, Clocks clocks, long travelMs) {
        return laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs, -1);
    }

    /**
     * @param skipIndex 试算抢占腾位时忽略的在途条目下标（-1 表示不忽略）
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        float halfWPx, Clocks clocks, long travelMs, int skipIndex) {
        for (int i = 0; i < actives.size(); i++) {
            if (i == skipIndex) continue;
            Active active = actives.get(i);
            if (active.mode() != mode || active.lane() != lane) continue;
            float widest = Math.max(newWPx, active.visual().textWPx() * active.visual().scalePercent());
            float span = 2 * halfWPx + widest;
            long advance = (long) Math.ceil(travelMs * Math.min(1.0, (widest + MIN_GAP_PX) / span));
            if (clocks.of(active.entry().source()) - active.startMs() < advance) return false;
        }
        return true;
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
    private static float lastAdvance(List<Active> actives, DanmakuMode mode, int lane, float halfWPx,
                                     Clocks clocks, long travelMs) {
        float newestElapsed = Float.MAX_VALUE;
        float newestWidth = -1.0f;
        for (Active active : actives) {
            if (active.mode() != mode || active.lane() != lane) continue;
            float elapsed = clocks.of(active.entry().source()) - active.startMs();
            if (elapsed < newestElapsed) {
                newestElapsed = elapsed;
                newestWidth = active.visual().textWPx() * active.visual().scalePercent();
            }
        }
        if (newestWidth < 0) return -1.0f;
        return newestElapsed / travelMs * (2 * halfWPx + newestWidth);
    }

    /** 条目寿命：滚动/逆向滚动按行程，固定项按驻留时长 */
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
        return ARGB.color(Math.round(alphaFactor * 255.0f), rgb & 0xFFFFFF);
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

    /** 断线/卸载世界时清空活动列表（BE 移除后 draw 不再被调，滞留条目靠此钩子回收） */
    public static void reset() {
        ACTIVE.clear();
        LAST_VIDEO_TIME.clear();
        ANOMALY.clear();
    }

    /** 安装 Store 清屏回调（懒注册一次）：换集/停止时立即释放该屏在途弹幕与场景态基线 */
    private static void installClearHook() {
        if (clearHookInstalled) return;
        clearHookInstalled = true;
        ClientDanmakuStore.addClearListener(pos -> {
            ACTIVE.remove(pos);
            LAST_VIDEO_TIME.remove(pos);
            ANOMALY.remove(pos);
        });
    }

    /** 滚动行程：基准 5s 除以速度倍率（所有滚动项同一速度，与弹幕数量无关） */
    private static long scrollTravelMs() {
        double speed = ClientConfig.CONFIG.danmakuSpeedMultiplier.get();
        return (long) (ClientDanmakuStore.INSTANT_DISPLAY_MS / speed);
    }

    /**
     * 空心文字框：四条细边一次提交（同一 RenderType 下四个矩形，框内不填充），保持文字下层（FRAME_Z）。
     * RenderType 懒构造一次后复用，避免逐帧重建。
     */
    private static void submitFrame(SubmitNodeCollector collector, PoseStack poseStack,
                                    float xMin, float xMax, float yMin, float yMax, float e, int color) {
        RenderType type = frameRenderType();
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            fillRect(buffer, pose, xMin, xMax, yMin, yMin + e, FRAME_Z, color);
            fillRect(buffer, pose, xMin, xMax, yMax - e, yMax, FRAME_Z, color);
            fillRect(buffer, pose, xMin, xMin + e, yMin + e, yMax - e, FRAME_Z, color);
            fillRect(buffer, pose, xMax - e, xMax, yMin + e, yMax - e, FRAME_Z, color);
        });
    }

    /** 文字框 RenderType（1x1 白纹理 + entityCutout）：懒构造一次后复用 */
    private static RenderType frameRenderType() {
        ensureFrameTexture();
        if (frameRenderType == null) frameRenderType = RenderTypes.entityCutout(FRAME_TEX);
        return frameRenderType;
    }

    /** CCW 顶点序: BL→BR→TR→TL（与 VideoScreenRenderer.fillBar 一致） */
    private static void fillRect(VertexConsumer vc, PoseStack.Pose pose,
                                 float xMin, float xMax, float yMin, float yMax, float z, int color) {
        vc.addVertex(pose, xMin, yMin, z).setColor(color).setUv(0, 0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, xMax, yMin, z).setColor(color).setUv(1, 0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, xMax, yMax, z).setColor(color).setUv(1, 1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, xMin, yMax, z).setColor(color).setUv(0, 1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, 0, 0, 1);
    }

    /** 1x1 纯白纹理（运行期注册一次），供实色矩形着色使用 */
    private static void ensureFrameTexture() {
        if (frameTexRegistered) return;
        frameTexRegistered = true;
        var white = new NativeImage(1, 1, false);
        white.setPixel(0, 0, 0xFFFFFFFF);
        Minecraft.getInstance().getTextureManager().register(FRAME_TEX,
            new DynamicTexture(() -> "danmaku_frame_white", white));
    }
}
