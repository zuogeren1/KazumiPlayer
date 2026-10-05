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
 * 保证更宽的追随弹幕（行程更快）在最坏时刻也不会追上前车。TOP/BOTTOM 固定项的槽位数同样由几何推导
 * （槽位数 = 车道数，见 {@link LaneGeometry#pinSlots()}）：<b>先占满全部可用行，用尽后才压叠</b>，
 * 压叠槽位按轮转游标在各槽位间分散（{@link DanmakuPinSlots}），不再固定挑「最接近释放」的同一槽。
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
 * <p>字号→世界换算：车道高 laneHPx = 9（字形行高）× 1.4（行距系数）× danmakuFontScale，只由「屏幕面高度
 * × 基准比例 × danmakuFontScale」决定（{@link #BASE_AREA_RATIO} 是像素域↔世界域的唯一锚点），
 * 与 danmakuAreaRatio 完全解耦——调显示区不再连累字号。车道数 = clamp(floor(显示带高 / laneHPx), 1,
 * {@link #MAX_LANE_COUNT})，故字号缩小即行距收紧、车道变多且铺满显示区；
 * 字号百分比只作用于条目自身的 pose 缩放 q_i = danmakuFontScale × fontSizePercent/100，故任一条目上下场
 * 都不会改变他人的位置与大小；q_i 会被钳制到单条车道高以内，避免超高条目溢出到相邻车道。基准换算因子
 * {@link #baseScale} 按 danmakuScaleWithScreen 取屏幕实际尺寸或固定基准半高——关闭缩放时字号
 * 在世界中恒为默认屏幕（3×2 格）折算的大小，不随屏幕方块尺寸变化（全屏 HUD 层不受该开关影响）。
 *
 * <p>显示区坐标：原点为屏幕面中心，显示带高 = danmakuAreaRatio × 屏幕面高（居中，像素域高见 {@link #bandH}）、
 * 宽 = 屏幕面宽，x 向为 SCROLL 的起跑侧、y 向为下缘侧。
 * 显示带下界按屏幕面底部 UI 安全区内缩（进度条高与其上缘，由调用方每帧传入；
 * 口径见 {@link LaneGeometry#keepOut}）：滚动/顶部带自显示带上缘向下、
 * 底部带自内缩下界向上铺开，四条带（含文字框与 REVERSE 镜像）连同字形外扩保护都不进入进度条所占区域。
 * 内缩量只由 UI 几何与字号决定、不随鼠标或 UI 显隐状态变化，故弹幕落点逐帧稳定不跳动；
 * 内缩后可用高不足一条车道时按 1 条车道处理并整排上移（越出显示带上缘而不是越过进度条）。
 * 车道自显示区上/下缘按 laneHPx 依次铺开，故车道区间恒在可用区内且互不重叠。条目位置一律按实际绘制宽度（textWPx × q）计算，
 * 位移不随字号缩放；字形按 (laneHPx - 9q)/2 在车道内垂直居中，故缩放后仍不越出本车道；
 * 完全移出显示区的条目不再提交，部分可见的条目只提交可见字符区间（{@link #visibleSlots}），
 * 屏幕外的字形不会进入渲染管线。
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

    /** 字形行高像素（原版字体 9px）与行距系数：车道高 = 9 × 1.4 × danmakuFontScale（两层共用） */
    static final float GLYPH_HEIGHT_PX = 9.0f;
    private static final float LINE_HEIGHT_FACTOR = 1.4f;
    /** 车道数上限：小字号时防显示区内车道过多（48 条足以在常见窗口下铺满显示区） */
    private static final int MAX_LANE_COUNT = 48;
    /** 100% 字号下的基准车道数：像素域↔世界域换算的基准显示带高由此定义 */
    private static final int BASE_LANE_COUNT = 5;
    /** 像素域基准显示带高 = 基准车道数 × 基准车道高（9×1.4）：{@link #BASE_AREA_RATIO} 下的显示带像素高 */
    private static final float BASE_BAND_H_PX = BASE_LANE_COUNT * GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR;
    /**
     * 显示区基准比例：像素域↔世界域换算的唯一锚点（取 danmakuAreaRatio 的默认值）。
     * 字号只由「屏幕面高度 × 本比例 × danmakuFontScale」决定，故 danmakuAreaRatio 只改变显示带高度
     * （进而改变可见车道数），不会连带缩放字号。
     */
    private static final float BASE_AREA_RATIO = 0.5f;
    /** 超长条目 DEBUG 阈值（字符槽数）：超过即准入时记一条（含字符数与来源），便于定位异常弹幕 */
    static final int LONG_ENTRY_CHARS = 512;
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
     * 可用下界 {@link #bottomLimit} = {@link #keepOut 可视下缘上限} − 字形外扩保护；车道数
     * lanes = clamp(floor(可用下界 / 车道高), 1, {@link #MAX_LANE_COUNT})——字号越小行距越紧、车道越多
     * （上限 {@link #MAX_LANE_COUNT}，足以铺满显示区）。车道高只由字号决定（显示区与底部内缩都不参与，
     * 否则调显示区会连累字号）。滚动/顶部带自显示区顶部向下、底部带自 {@link #bottomLimit} 向上铺开，
     * 故车道区间互不重叠；车道堆叠在可用下界之内放不下时整排上移贴住可用下界，越出显示带上缘而不是
     * 越过底部 UI。{@link DanmakuHudLayer} 以等比画面像素高为显示区高、控制条高为内缩调用同一实现。
     */
    static final class LaneGeometry {

        /** 显示区（显示带）高（像素域）：HUD 层=画面高×danmakuAreaRatio，世界层={@link #bandH} */
        final float areaH;
        /** 单条车道高 = 9 × 1.4 × danmakuFontScale（只随字号，不含显示区与内缩因素） */
        final float laneH;
        /** 车道数 = clamp(floor(可用下界 / laneH), 1, {@link #MAX_LANE_COUNT}) */
        final int lanes;
        /** 可用下界（带内坐标：0=显示带上缘、向下为正）= max(0, 可视下缘上限 − 字形外扩保护) */
        final float bottomLimit;
        /** 车道堆叠的整体上移量 = max(0, 车道数×车道高 − 可用下界)：只在可用高不足时非 0 */
        private final float overflow;

        private LaneGeometry(float areaH, float laneH, int lanes, float bottomLimit, float overflow) {
            this.areaH = areaH;
            this.laneH = laneH;
            this.lanes = lanes;
            this.bottomLimit = bottomLimit;
            this.overflow = overflow;
        }

        /**
         * 条目可视下缘的上限（显示区像素，带内坐标：0=显示带上缘、向下为正）：
         * min(显示带下缘, 底部 UI 上缘) − UI 占用。显示带下缘在 UI 上缘之上时（常态）即「按 UI 高度
         * 自显示带下缘内缩」；显示带越过屏幕面下缘、UI 上缘落进显示带内时（固定字号基准模式下屏幕面
         * 比基准还小）改为收到 UI 上方——两种情形都不会让弹幕（含文字框与字形外扩）压住 UI。两层共用本式。
         *
         * @param areaH       显示区（显示带）高（显示区像素）
         * @param uiReservePx 底部 UI 占用（显示区像素）：世界层=进度条高+它与屏幕面下缘的间距折算，
         *                    HUD 层=控制条高；负值与 0 同义
         * @param uiTopPx     底部 UI 上缘（显示区像素，带内坐标）
         */
        static float keepOut(float areaH, float uiReservePx, float uiTopPx) {
            return Math.min(areaH, uiTopPx) - Math.max(0.0f, uiReservePx);
        }

        /**
         * @param areaH     显示区（显示带）高，不含字号因素（HUD 层=画面高×danmakuAreaRatio；世界层={@link #bandH}）
         * @param fontScale danmakuFontScale
         * @param keepOut   条目可视下缘上限（显示区像素，带内坐标）：见 {@link #keepOut(float, float, float)}
         */
        static LaneGeometry of(float areaH, float fontScale, float keepOut) {
            // 车道高只由字号决定（与显示区无关）：可用高不足一条车道时也保留 1 条车道
            float laneH = GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR * fontScale;
            // 字形外扩保护：投影/描边在行高之外再外扩 1 个局部像素，按单条最大缩放（车道高/行高）折算
            float overhang = laneH / GLYPH_HEIGHT_PX;
            float bottomLimit = Math.max(0.0f, keepOut - overhang);
            // 浮点误差兜底：可用下界恰为整数倍车道高时（如 63 / 12.6）仍判为整除
            int lanes = Math.max(1, Math.min(MAX_LANE_COUNT,
                (int) Math.floor(bottomLimit / (double) laneH + 1.0e-5)));
            return new LaneGeometry(areaH, laneH, lanes, bottomLimit,
                Math.max(0.0f, lanes * laneH - bottomLimit));
        }

        /** 车道自显示区顶部起的上缘（滚动带/顶部带）；底部带用 {@link #bottomOf(int)} */
        float topOf(int lane) {
            return lane * laneH - overflow;
        }

        /** 车道自可用下界起的上缘（底部带，槽位 0 最靠下；可用高不足时整排上移，其下缘恒为 {@link #bottomLimit}） */
        float bottomOf(int lane) {
            return bottomLimit - (lane + 1) * laneH;
        }

        /** 单条条目的缩放上限：超出即压到相邻车道（= 车道高 / 字形行高） */
        float maxEntryScale() {
            return laneH / GLYPH_HEIGHT_PX;
        }

        /**
         * 固定槽位数 = 车道数：槽位高即车道高（一行一个固定项），可用区放得下几行就有几个槽位——
         * 由「可用下界 / 车道高」推导（见 {@link #lanes} 与 {@link #bottomLimit}），
         * 不存在写死的行数上限；用尽后由 {@link DanmakuPinSlots} 轮转分散压叠。
         */
        int pinSlots() {
            return lanes;
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
        private int forcedVideo;
        private int skipped;

        /**
         * 本帧是否出现异常计数（无异常即完全静默）。forced-overlap-video 不计入：开启
         * danmakuAllowOverlap 后视频条目压叠是预期行为，不该刷异常行。
         */
        static boolean isAnomaly(int noLane, int capped, int evicted, int forced, int skipped) {
            return noLane > 0 || capped > 0 || evicted > 0 || forced > 0 || skipped > 0;
        }

        /**
         * 投入一帧计数。
         *
         * @return null=静默（本帧无异常，或异常仍在聚合窗口内）；否则为应打印的内容
         */
        Report submit(long nowMs, int dueCount, int admittedCount, int noLaneCount, int cappedCount,
                      int evictedCount, int forcedCount, int forcedVideoCount, int skippedCount) {
            if (!isAnomaly(noLaneCount, cappedCount, evictedCount, forcedCount, skippedCount)) return null;
            frames++;
            due += dueCount;
            admitted += admittedCount;
            dropped += noLaneCount + cappedCount + skippedCount;
            noLane += noLaneCount;
            capped += cappedCount;
            evicted += evictedCount;
            forced += forcedCount;
            forcedVideo += forcedVideoCount;
            skipped += skippedCount;
            if (reported && nowMs - lastEmitMs < WINDOW_MS) return null;
            Report report = new Report(frames > 1, frames, due, admitted, dropped, noLane, capped,
                evicted, forced, forcedVideo, skipped);
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
            forcedVideo = 0;
            skipped = 0;
            return report;
        }

        /**
         * 应打印的异常日志内容。
         *
         * @param aggregate true=窗口累计行（frames&gt;1，标明为聚合）；false=首个异常帧的明细行
         */
        record Report(boolean aggregate, int frames, int due, int admitted, int dropped, int noLane,
                      int capped, int evicted, int forced, int forcedVideo, int skipped) {}
    }

    /** 异常日志行的统一格式（两层同口径）：aggregate 标记窗口累计行 */
    static void logAnomalies(String layer, BlockPos pos, AnomalyLog.Report report, int activeCount) {
        KazumiLog.danmaku.debug(
            "{} danmaku anomalies{} at {} (frames {}, due {}, admitted {}, dropped {}, no-free-lane {}, "
                + "capped-video {}, evicted-for-social {}, forced-overlap {}, forced-overlap-video {}, "
                + "advanced-skipped {}, active {})",
            layer, report.aggregate() ? " aggregated" : "", pos, report.frames(), report.due(),
            report.admitted(), report.dropped(), report.noLane(), report.capped(), report.evicted(),
            report.forced(), report.forcedVideo(), report.skipped(), activeCount);
    }

    /**
     * 准入时一次算好的绘制参数（绘制循环逐帧只读，零重算、零分配）。
     *
     * @param text         视觉序文本（入场时 {@code getVisualOrderText()} 一次，此后不再重排）
     * @param index        字符槽索引（前缀推进宽度 + 原始位置）：可见区间二分与子序列撇取都读它
     * @param textWPx      条目文本基础像素宽（未乘 {@code scalePercent}）
     * @param scalePercent 条目 pose 缩放（danmakuFontScale × fontSizePercent/100，钳制到车道高以内）
     * @param color        主色（已按 danmakuOpacity 合成 alpha；房间互发恒金）
     * @param outlineColor 描边色（已合成 alpha，未开启描边时不被使用）
     * @param frameColor   文字框色（已合成 alpha）
     * @param frameInk     房间互发弹幕的字形外接框（{@code prepareText} 一次，以 y=0 为基准），其余来源为 null
     */
    private record Visual(FormattedCharSequence text, CharIndex index, float textWPx, float scalePercent,
                          int color, int outlineColor, int frameColor, ScreenRectangle frameInk) {
        /** 字符槽数 */
        int chars() {
            return index.chars();
        }
    }

    /**
     * 视觉序文本的字符槽索引（准入时一次，随 {@link Visual} 缓存）：可见区间裁剪靠它二分，
     * 绘制期不再重排文本、也不再重算全串宽度。
     *
     * @param positions positions[i] = 第 i 个字符槽在视觉序序列中的原始位置，末位为结束位置（供撇取子序列用）
     * @param prefix    prefix[i] = 前 i 个字符槽的推进宽度（未缩放域，升序；末位为整串宽度）
     */
    record CharIndex(int[] positions, float[] prefix) {
        int chars() {
            return prefix.length - 1;
        }
    }

    /**
     * 建立字符槽索引：两遍 {@code accept}（先计数再填表），逐码点用 {@code Font#width} 取单字符推进宽度
     * （与 {@code Font#width(FormattedCharSequence)} 同一口径，仅逐字符向上取整）。整体 O(字符数)，
     * 只在准入时调用一次。
     */
    static CharIndex indexChars(Font font, FormattedCharSequence text) {
        int[] count = new int[1];
        text.accept((position, style, codepoint) -> {
            count[0]++;
            return true;
        });
        int chars = count[0];
        int[] positions = new int[chars + 1];
        float[] prefix = new float[chars + 1];
        int[] cursor = new int[1];
        int[] tail = new int[2];
        text.accept((position, style, codepoint) -> {
            int i = cursor[0]++;
            positions[i] = position;
            prefix[i + 1] = prefix[i] + font.width(FormattedCharSequence.codepoint(codepoint, style));
            tail[0] = position;
            tail[1] = Character.charCount(codepoint);
            return true;
        });
        positions[chars] = chars == 0 ? 0 : tail[0] + tail[1];
        return new CharIndex(positions, prefix);
    }

    /** 可见字符槽区间打包：高 32 位=起点，低 32 位=终点（不含） */
    static long packRange(int start, int end) {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    /** 打包区间的起点 */
    static int rangeStart(long range) {
        return (int) (range >> 32);
    }

    /** 打包区间的终点（不含） */
    static int rangeEnd(long range) {
        return (int) range;
    }

    /**
     * 可见字符槽区间：在前缀推进宽度数组上二分，求与视图可见跨度 [visStart, visEnd)（已缩放域，相对文字起点）
     * 相交的字符槽 [start, end)——跨界的那一个字符槽算可见，故提交数最多比严格可见槽数多 1。
     * 完全不可见返回空区间（start == end）。
     *
     * @param prefix   前缀推进宽度（未缩放域，升序）
     * @param scale    条目 pose 缩放
     * @param visStart 可见跨度起点（已缩放域，为负按 0 处理）
     * @param visEnd   可见跨度终点（已缩放域）
     */
    static long visibleSlots(float[] prefix, float scale, float visStart, float visEnd) {
        int chars = prefix.length - 1;
        if (chars <= 0 || visEnd <= visStart || scale <= 0.0f) return packRange(0, 0);
        float lo = Math.max(0.0f, visStart) / scale;
        float hi = visEnd / scale;
        if (hi <= 0.0f || lo >= prefix[chars]) return packRange(0, 0);
        int start = Math.min(chars - 1, Math.max(0, firstAbove(prefix, lo) - 1));
        int end = Math.min(chars, firstAtLeast(prefix, hi));
        return packRange(start, Math.max(start, end));
    }

    /** 二分：首个 prefix[i] &gt; value 的下标 */
    private static int firstAbove(float[] prefix, float value) {
        int low = 0;
        int high = prefix.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (prefix[mid] <= value) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    /** 二分：首个 prefix[i] &gt;= value 的下标 */
    private static int firstAtLeast(float[] prefix, float value) {
        int low = 0;
        int high = prefix.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (prefix[mid] < value) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    /**
     * 可见区间过滤器（每帧每个被裁剪条目 1 个小对象）：保留原始位置落在
     * [positions[start], positions[end]) 的字符，位置与样式原样透传，故子序列相对整串的偏移可由
     * {@code prefix[start]} 还原。
     */
    static FormattedCharSequence slice(FormattedCharSequence text, int[] positions, int start, int end) {
        int from = positions[start];
        int to = positions[end];
        return output -> text.accept((position, style, codepoint) ->
            position < from || (position < to && output.accept(position, style, codepoint)));
    }

    /**
     * 车道号收口（纯函数）：屏幕面尺寸/显示区/字号变化后，把在屏条目已缓存的越界车道号收进
     * [0, laneCount-1]，避免它们停在显示带之外（几何其余部分每帧重算，无尺寸相关缓存）。
     */
    static int clampLane(int lane, int laneCount) {
        return Math.max(0, Math.min(laneCount - 1, lane));
    }

    /** 超长条目 DEBUG（准入时一条，含字符数与来源）：不影响 displayAt 与车道准入语义 */
    static void logLongEntry(int chars, DanmakuSource source, BlockPos pos) {
        if (chars > LONG_ENTRY_CHARS) {
            KazumiLog.danmaku.debug("Danmaku long entry: {} chars from {} at {}", chars, source, pos);
        }
    }

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
    /** 每屏上次见到的车道数：车道数变化（屏幕面尺寸/显示区/字号变化）即收口在屏条目的车道号 */
    private static final Map<BlockPos, Integer> LAST_LANES = new ConcurrentHashMap<>();
    private static long seqCursor;
    /** 滚动车道轮转游标：多条同时可入时用于分散到不同车道 */
    private static int laneCursor;
    /** 固定槽位分配器（TOP/BOTTOM 各一份轮转游标）：有空槽必占空槽，槽位用尽后在各槽位间轮转分散压叠 */
    private static final DanmakuPinSlots PIN_TOP = new DanmakuPinSlots();
    private static final DanmakuPinSlots PIN_BOTTOM = new DanmakuPinSlots();
    private static boolean clearHookInstalled;

    private DanmakuWorldLayer() {}

    /**
     * 渲染帧入口：消费到期条目并绘制活动弹幕。
     * 调用方保证已处于屏幕面局部坐标（translate+rotateToFacing 之后、popPose 之前）。
     *
     * @param bottomReserveWorld 屏幕面底部 UI 占用（世界单位/格）：进度条高 + 它与屏幕面下缘的间距
     * @param bottomUiGapWorld   进度条上缘到屏幕面下缘的间距（世界单位/格）：显示带越过面下缘时据此定位
     *                           进度条上缘（见 {@link LaneGeometry#keepOut}）
     */
    public static void draw(SubmitNodeCollector collector, PoseStack poseStack,
                            VideoScreenRenderState state, float halfW, float halfH,
                            float bottomReserveWorld, float bottomUiGapWorld) {
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

        // 车道几何每帧由当前屏幕面尺寸重算（本帧 halfH/halfW），无按屏几何缓存：
        // 车道高 = 9×1.4×danmakuFontScale（只随字号），车道数由显示带高（danmakuAreaRatio）
        // 扣除底部进度条占用后的可用下界推导
        float baseScale = baseScale(config.danmakuScaleWithScreen.get() ? halfH : FIXED_SCALE_BASIS_HALF_H);
        float halfWPx = halfW / baseScale;
        float fontScale = config.danmakuFontScale.get().floatValue();
        float areaH = bandH(config.danmakuAreaRatio.get().floatValue());
        // 底部 UI 安全区：进度条以世界单位绘制，按本层像素↔世界换算因子折算成显示区像素（与字号解耦）；
        // 带顶在像素域为 0、其世界高度由显示带半高换算，故条上缘 = 半高 + (屏幕面半高 + 间距)/baseScale
        float uiReservePx = baseScale > 0.0f ? Math.max(0.0f, bottomReserveWorld) / baseScale : 0.0f;
        float uiTopPx = areaH / 2.0f + (baseScale > 0.0f
            ? (halfH + Math.max(0.0f, bottomUiGapWorld)) / baseScale : 0.0f);
        LaneGeometry lanes = LaneGeometry.of(areaH, fontScale,
            LaneGeometry.keepOut(areaH, uiReservePx, uiTopPx));
        boolean allowOverlap = config.danmakuAllowOverlap.get();
        // 尺寸变化失效：几何无缓存，但准入时定下的车道号可能越界 → 收进最后一条车道并记一条 DEBUG
        Integer lastLanes = LAST_LANES.put(pos, lanes.lanes);
        if (lastLanes != null && lastLanes != lanes.lanes) {
            int moved = 0;
            for (int i = 0; i < actives.size(); i++) {
                Active active = actives.get(i);
                int clamped = clampLane(active.lane(), lanes.lanes);
                if (clamped == active.lane()) continue;
                actives.set(i, new Active(active.entry(), active.visual(), active.startMs(), active.seq(),
                    active.mode(), active.travelMs(), clamped));
                moved++;
            }
            KazumiLog.danmaku.debug("World layer geometry changed at {}: lanes {} -> {}, reclamped {} of {}",
                pos, lastLanes, lanes.lanes, moved, actives.size());
        }
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
            // 单条占用高度按实际缩放算并钳制到车道高以内，避免超大字号溢出压到相邻车道
            float entryScale = Math.min(fontScale * scalePercent, lanes.maxEntryScale());
            float textWPx = font.width(text);
            int color = entryColor(entry, alphaFactor);
            // 字符槽索引：可见区间裁剪一次建表；超长条目另记一条 DEBUG 便于定位异常弹幕
            CharIndex index = indexChars(font, text);
            logLongEntry(index.chars(), entry.source(), pos);
            Visual visual = new Visual(text, index, textWPx, entryScale, color, outlineColorBase,
                frameColorBase, entry.source() == DanmakuSource.ROOM_CHAT
                    ? inkBounds(font, text, 0.0f, color, !outline) : null);
            Admission adm = admit(entry, mode, visual, actives, clocks, travelMs, halfWPx, lanes,
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
        AnomalyLog.Report report = ANOMALY.computeIfAbsent(pos, k -> new AnomalyLog())
            .submit(clocks.monoMs(), due.size(), accepted, noLane, capped, evicted, forced, forcedVideo,
                skipped);
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
     * 像素域→世界域的基准缩放（纯函数）：只由「换算基准半高 × {@link #BASE_AREA_RATIO}」决定，与
     * danmakuAreaRatio 完全无关——改显示区只改变可见车道数，不会连带缩放字号。调用方按
     * danmakuScaleWithScreen 决定传屏幕面半高还是固定基准半高 {@link #FIXED_SCALE_BASIS_HALF_H}
     * （默认屏幕尺寸），关闭缩放时字号不随屏幕方块尺寸变化。
     *
     * @param basisHalfH 换算基准半高（世界单位/格）
     */
    static float baseScale(float basisHalfH) {
        return (2 * basisHalfH * BASE_AREA_RATIO) / BASE_BAND_H_PX;
    }

    /**
     * 显示带高（像素域）：danmakuAreaRatio 只决定显示区大小（世界层以 {@link #BASE_AREA_RATIO} 为
     * 像素域↔世界域锚点），字号不参与——车道数 = clamp(floor(本值 / 车道高), 1, 48)。
     */
    static float bandH(float areaRatio) {
        return BASE_BAND_H_PX * areaRatio / BASE_AREA_RATIO;
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
            if (outsideDisplay(x, yTop, drawnWPx, lanes, halfWPx)) continue;
            // 部分可见：只提交可见字符槽区间（前缀宽度二分，绘制期不重算全串宽度）
            float half = lanes.areaH / 2.0f;
            long range = visibleSlots(visual.index().prefix(), q, Math.max(0.0f, -halfWPx - x),
                Math.min(drawnWPx, halfWPx - x));
            int start = rangeStart(range);
            int end = rangeEnd(range);
            if (start >= end) continue;
            boolean clipped = start > 0 || end < visual.chars();
            FormattedCharSequence seq = clipped
                ? slice(visual.text(), visual.index().positions(), start, end) : visual.text();
            // 子序列左端对齐：局部域起点右移 prefix[start]（未缩放域），使可见首字仍落在原位
            float shift = clipped ? visual.index().prefix()[start] : 0.0f;
            // 字形在车道内垂直居中：字号缩放后仍不越出本车道与可用区
            float localTextTop = textTopLocal(q, lanes.laneH);

            poseStack.pushPose();
            poseStack.translate(x, yTop, 0.0f);
            poseStack.scale(q, q, 1.0f);
            ScreenRectangle ink = visual.frameInk();
            if (ink != null) {
                // 缓存外接框以 y=0 为基准：绘制期补上条目垂直偏移，内边距除以 q 后屏幕上恒为 FRAME_PAD；
                // 文字框同时裁到显示带内与底部 UI 内缩下界内，屏幕外与进度条区内的框边不再提交
                float pad = FRAME_PAD / q;
                float frameLeft = Math.max(x + q * (ink.left() - pad), -halfWPx);
                float frameRight = Math.min(x + q * (ink.right() + pad), halfWPx);
                float frameTop = ink.top() + localTextTop - pad;
                float frameBottom = Math.min(ink.bottom() + localTextTop + pad,
                    (-lanes.areaH / 2.0f + lanes.bottomLimit - yTop) / q);
                if (frameRight > frameLeft && frameBottom > frameTop) {
                    submitFrame(collector, poseStack, (frameLeft - x) / q, (frameRight - x) / q,
                        frameTop, frameBottom, FRAME_EDGE / q, visual.frameColor());
                }
            }
            collector.submitText(poseStack, shift, localTextTop, seq,
                !outline, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, visual.color(), 0,
                outline ? visual.outlineColor() : 0);
            poseStack.popPose();
        }
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

    /** 条目落点纵坐标（y 向为下缘侧）：车道自显示带（居中于屏幕面）上/下缘按车道高铺开 */
    private static float placementYTop(Active active, LaneGeometry lanes) {
        return -lanes.areaH / 2.0f + (active.mode() == DanmakuMode.BOTTOM
            ? lanes.bottomOf(active.lane()) : lanes.topOf(active.lane()));
    }

    /** 行程进度 p∈[0,1]：按条目自身时钟推进（片内弹幕暂停即冻结；固定项 travelMs=0 不参与行程） */
    private static float progress(Active active, Clocks clocks) {
        int travelMs = active.travelMs();
        if (travelMs <= 0) return 0.0f;
        return (clocks.of(active.entry().source()) - active.startMs()) / (float) travelMs;
    }

    /**
     * 字形在车道内的垂直居中偏移（条目局部域，即 {@code submitText}/{@code prepareText} 的行顶 y）：
     * 行高 9q 与车道高 laneH 的差值取半后折算回局部域，故条目按自身缩放放大后仍居中于本车道、
     * 不越出车道上下缘。两层共用同一实现（HUD 层的 {@code inkBounds} 与文字绘制同取本值）。
     */
    static float textTopLocal(float q, float laneH) {
        return (laneH - GLYPH_HEIGHT_PX * q) / (2.0f * q);
    }

    /** 整条落在显示区之外（横向按屏幕面宽度、纵向按显示带）即不提交：按绘制外接框求交 */
    private static boolean outsideDisplay(float x, float yTop, float drawnWPx, LaneGeometry lanes,
                                          float halfWPx) {
        float half = lanes.areaH / 2.0f;
        return x >= halfWPx || x + drawnWPx <= -halfWPx
            || yTop >= half || yTop + lanes.laneH <= -half;
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
     * @param visual       准入时算好的绘制参数（文本/基础宽度/缩放/配色/文字框外接框）
     * @param lanes        本帧车道几何（车道高与车道数随字号缩放）
     * @param allowOverlap danmakuAllowOverlap：开启后视频片内条目无空车道时压叠上屏而非丢弃
     * @return 准入结果；active 为 null 表示丢弃该条
     */
    private static Admission admit(DanmakuEntry entry, DanmakuMode mode, Visual visual,
                                   List<Active> actives, Clocks clocks, long travelMs, float halfWPx,
                                   LaneGeometry lanes, boolean allowOverlap) {
        long seq = seqCursor++;
        long startMs = clocks.of(entry.source());
        int pinSlots = lanes.pinSlots();
        return switch (mode) {
            case TOP -> pinAdmit(entry, visual, actives, DanmakuMode.TOP, seq, startMs, pinSlots,
                allowOverlap);
            case BOTTOM -> pinAdmit(entry, visual, actives, DanmakuMode.BOTTOM, seq, startMs, pinSlots,
                allowOverlap);
            case SCROLL, REVERSE -> {
                // 占位宽度按条目实际缩放后的绘制宽度计（与 HUD 侧同判据）
                LanePick pick = pickLane(actives, mode, visual.textWPx() * visual.scalePercent(), halfWPx,
                    clocks, travelMs, !isVideoDanmaku(entry.source()), allowOverlap, lanes.lanes);
                if (pick.lane() < 0) yield Admission.DROPPED;
                Active victim = pick.evictIndex() >= 0 ? actives.remove(pick.evictIndex()) : null;
                yield new Admission(new Active(entry, visual, startMs, seq, mode, (int) travelMs,
                    pick.lane()), victim, pick.forced());
            }
            case ADVANCED -> Admission.DROPPED;
        };
    }

    /**
     * 固定项准入：槽位数 = 车道数（由显示带高与车道高推导，见 {@link LaneGeometry#pinSlots()}）。
     * 有空槽必占空槽；槽位用尽后社交条目恒压叠、视频片内条目仅在 danmakuAllowOverlap 开启时压叠，
     * 压叠槽位由 {@link DanmakuPinSlots} 按轮转游标在各槽位间分散（不再固定挑「最接近释放」的同一槽），
     * 口径与滚动车道一致；无槽可用（槽位数为 0）时丢弃。
     */
    private static Admission pinAdmit(DanmakuEntry entry, Visual visual, List<Active> actives,
                                      DanmakuMode pinMode, long seq, long startMs, int pinSlots,
                                      boolean allowOverlap) {
        boolean[] used = pinUsage(actives, pinMode, pinSlots);
        DanmakuPinSlots.Pick pick = pinAllocator(pinMode).allocate(used);
        if (pick.slot() < 0) return Admission.DROPPED;
        if (pick.forced() && isVideoDanmaku(entry.source()) && !allowOverlap) return Admission.DROPPED;
        return new Admission(new Active(entry, visual, startMs, seq, pinMode, 0, pick.slot()), null,
            pick.forced());
    }

    /**
     * 固定槽位占用情况（长度 = 本帧槽位数）：被在途条目占用的槽位不可复用；
     * 越界车道号（几何缩水后尚未收口）不计入占用，与 {@link #clampLane} 同口径。
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
    private static LanePick pickLane(List<Active> actives, DanmakuMode mode, float newWPx, float halfWPx,
                                     Clocks clocks, long travelMs, boolean social, boolean allowOverlap,
                                     int laneCount) {
        for (int offset = 0; offset < laneCount; offset++) {
            int lane = Math.floorMod(laneCursor + offset, laneCount);
            if (laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs)) {
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
                if (!laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs, victim)) continue;
                laneCursor = Math.floorMod(lane + 1, laneCount);
                return new LanePick(lane, victim, false);
            }
        }
        // 压叠兜底：社交条目恒定可用；视频片内条目仅在 danmakuAllowOverlap 开启时可用
        if (!social && !allowOverlap) return new LanePick(-1, -1, false);
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
        LAST_LANES.clear();
        PIN_TOP.reset();
        PIN_BOTTOM.reset();
    }

    /** 安装 Store 清屏回调（懒注册一次）：换集/停止时立即释放该屏在途弹幕与场景态基线 */
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
