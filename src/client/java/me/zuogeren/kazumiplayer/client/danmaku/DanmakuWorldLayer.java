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
 * 停住处继续；房间互发与直播弹幕用本地单调钟，不受暂停影响。行程进度、驻留时长与水平让位判据
 * 的推进量都取该条自身的时钟。
 *
 * <p>滚动/逆向条目的纵向放置是<b>连续</b>的（{@link DanmakuPack}，两层共用同一实现）：落点由在途条目的
 * 边界推导，只要求与「共存期间可能发生水平重叠」的在途条目保持至少 {@link DanmakuPack#MIN_SEP_PX}
 * 像素的纵向间隔，故行距 = 条目自身文本框高（字形行高 × 该条缩放）+ 间隔，不落在整行网格上，
 * 不同字号的条目可互相嵌入对方的剩余空间。水平判据仍是占用感知的：同向在途条目的已推进像素须 ≥
 * max(新条绘制宽, 该条绘制宽) + {@link DanmakuPack#MIN_GAP_PX} 才算让出（按行程比例换算成时间提前量、毫秒向上取整），
 * 占位宽度取两者的较大值，保证更宽的追随弹幕（行程更快）在最坏时刻也不会追上前车；
 * 逆向与滚动互为镜像、必然中途交会，故异向滚动条目一律纵向分离。
 * TOP/BOTTOM 固定项保持槽位语义，槽位数 = 车道数（见 {@link LaneGeometry#pinSlots()}）：
 * <b>先占满全部可用行，用尽后才压叠</b>，压叠槽位按轮转游标在各槽位间分散（{@link DanmakuPinSlots}）。
 *
 * <p>社交优先：出队后按来源优先级（ROOM_CHAT &gt; BILIBILI_LIVE &gt; BILIBILI_VIDEO）稳定排序再准入；
 * {@code config.danmakuScreenCap()} 容量闸只拦视频片内条目，社交条目不受上限约束（仍计入在屏数）；
 * 滚动/逆向的社交条目优先取无碰撞落点，其次按行程进度降序试算抢占视频片内条目腾位（移除后确实腾出落点
 * 才腾位、不白丢视频条目），仍无落点时与在途条目纵向压叠（该条豁免水平让位不变量，计入 forced-overlap
 * DEBUG 计数）——即社交条目永不丢弃；视频片内条目无落点时按压叠开关压叠或丢弃（no-free-lane）。
 *
 * <p>模式：SCROLL 右进左出、REVERSE 左进右出（镜像）；TOP/BOTTOM 居中驻留 {@link #PIN_HOLD_MS}，
 * 不受 speedMultiplier 影响。
 *
 * <p>开关：来源三开关与模式三开关过滤出队条目；danmakuShowColored=false 时跳过颜色非白的
 * B 站条目（房间互发恒金字不受影响）；danmakuShowAdvanced=true 时把高级弹幕（B 站 mode 7/8/9）
 * 按其文本按时间降级为普通滚动，降级后与普通条目同样参与落点准入，false 则跳过。
 *
 * <p>来源差异：房间互发（{@link DanmakuSource#ROOM_CHAT}）按「昵称：内容」组装并染
 * {@link #ROOM_CHAT_COLOR} 金色、外描一个空心矩形文字框；B 站弹幕按包内自身颜色渲染、不画框。
 * 文字框矩形由字形实际绘制外接框（{@code Font#prepareText} 的 bounds）四边各外扩 {@link #FRAME_PAD}
 * 像素推导，文字在框内水平与垂直都居中。文字框与描边的 alpha 一律由 danmakuOpacity 控制。
 *
 * <p>字号→世界换算：车道高 laneHPx = 9（字形行高）× 1.4（行距系数）× danmakuFontScale，只由「屏幕面高度
 * × 基准比例 × danmakuFontScale」决定（{@link #BASE_AREA_RATIO} 是像素域↔世界域的唯一锚点），
 * 与 danmakuAreaRatio 完全解耦——调显示区不再连累字号。车道数 = clamp(floor(显示带高 / laneHPx), 1,
 * {@link #MAX_LANE_COUNT})，故字号缩小即行距收紧、固定槽位变多且铺满显示区（滚动/逆向条目的行距另由
 * 自身文本框高决定，见 {@link DanmakuPack}）；
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
 * 固定槽位自显示区上/下缘按 laneHPx 依次铺开，故槽位区间恒在可用区内且互不重叠；滚动/逆向条目按
 * {@link DanmakuPack} 的连续落点铺开，行距 = 自身文本框高 + 间隔。条目位置一律按实际绘制宽度（textWPx × q）计算，
 * 位移不随字号缩放；固定项字形按 (laneHPx - 9q)/2 在槽位内垂直居中，滚动/逆向条目的字形行顶即落点上缘，
 * 故缩放后都不越出各自的占用区；
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
 * <p>深度分层（{@link DanmakuDepthLayers}）：屏幕面前方沿法线分出若干平行层来提高同屏可见条数——
 * 新条目按轮转落到各层，每层各持一份打包器与固定槽位游标，<b>层内</b>仍执行上面全部纵向打包与碰撞
 * 判据（同层零相交不变量不变），<b>层间</b>不做相交判定（层间距 0.05–2.0 格远大于文本框厚度）；
 * 同屏上限按层数等比放大（见 {@link DanmakuDepthLayers#effectiveScreenCap}），否则层数只会让每层变稀；
 * 绘制时逐层沿屏幕面法线朝观察者平移 {@code layerIndex × danmakuDepthSpacing} 格（世界单位、
 * 在像素域缩放之前施加），层序即远→近；层数=1 时不产生任何平移，绘制路径与单层实现逐位一致。
 * 全屏 HUD 层（{@link DanmakuHudLayer}）为二维画面，不做深度分层、不受本功能影响。
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
    private static final int BASE_LANE_COUNT = 13;
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
    /** danmakuScaleWithScreen=false 时的固定世界字号基准：默认屏幕尺寸（3×2 格）的半高 */
    private static final float FIXED_SCALE_BASIS_HALF_H = 1.0f;
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 白色弹幕过滤基准色（danmakuShowColored=false 时保留的 B 站条目颜色） */
    private static final int WHITE_RGB = 0xFFFFFF;
    /** 房间互发弹幕文字框内边距（显示区像素，四边相等，与 {@link DanmakuPack} 的占位口径同源）与线宽 */
    private static final float FRAME_PAD = DanmakuPack.FRAME_PAD_PX;
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

        /** 固定槽位自显示区顶部起的上缘（TOP 带）；BOTTOM 带用 {@link #bottomOf(int)} */
        float topOf(int lane) {
            return lane * laneH - overflow;
        }

        /** 固定槽位自可用下界起的上缘（BOTTOM 带，槽位 0 最靠下；可用高不足时整排上移，其下缘恒为 {@link #bottomLimit}） */
        float bottomOf(int lane) {
            return bottomLimit - (lane + 1) * laneH;
        }

        /** 单条条目的缩放上限（= 车道高 / 字形行高）：固定槽位与滚动行距都由该上限兜住 */
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
     * 固定槽位号收口（纯函数）：屏幕面尺寸/显示区/字号变化后，把在屏固定项已缓存的越界槽位号收进
     * [0, laneCount-1]，避免它们停在显示带之外（几何其余部分每帧重算，无尺寸相关缓存）。
     */
    static int clampLane(int lane, int laneCount) {
        return Math.max(0, Math.min(laneCount - 1, lane));
    }

    /** 超长条目 DEBUG（准入时一条，含字符数与来源）：不影响 displayAt 与落点准入语义 */
    static void logLongEntry(int chars, DanmakuSource source, BlockPos pos) {
        if (chars > LONG_ENTRY_CHARS) {
            KazumiLog.danmaku.debug("Danmaku long entry: {} chars from {} at {}", chars, source, pos);
        }
    }

    /**
     * 在屏条目。
     *
     * @param startMs 该条驱动时钟在准入时刻的取值（社交=单调钟，片内=播放器位置）
     * @param travelMs 滚动行程（固定项为 0）
     * @param slot    固定项槽位号（滚动/逆向为 -1）
     * @param top     滚动/逆向条目的文本框上缘（显示区坐标）；固定项不用
     * @param height  滚动/逆向条目的文本框高（字形行高 × 缩放）；固定项不用
     */
    private record Active(DanmakuEntry entry, Visual visual, long startMs, long seq, DanmakuMode mode,
                          int travelMs, int slot, float top, float height)
        implements DanmakuPack.Item {

        @Override
        public DanmakuSource source() {
            return entry.source();
        }

        @Override
        public float scale() {
            return visual.scalePercent();
        }

        @Override
        public float width() {
            return visual.textWPx() * visual.scalePercent();
        }

        /** 槽位收口后的副本（几何变化时才新建） */
        Active withSlot(int newSlot) {
            return new Active(entry, visual, startMs, seq, mode, travelMs, newSlot, top, height);
        }

        /** 落点收口后的副本（几何变化时才新建） */
        Active withTop(float newTop) {
            return new Active(entry, visual, startMs, seq, mode, travelMs, slot, newTop, height);
        }
    }

    /** 帧时钟：社交弹幕（房间/直播）用单调钟，片内弹幕用播放器位置——暂停期后者冻结 */
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

    /**
     * 容量闸：只拦视频片内条目（社交条目不受上限约束，始终优先上屏）。
     *
     * @param screenCap 有效同屏上限 = danmakuScreenCap() × 层数（见 {@link DanmakuDepthLayers#effectiveScreenCap}）
     */
    static boolean capBlocks(DanmakuSource source, int activeCount, int screenCap) {
        return isVideoDanmaku(source) && activeCount >= screenCap;
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

    /** 每屏场景态：各深度层一份在屏条目列表 + 轮转分配游标 */
    private static final Map<BlockPos, DepthScene> ACTIVE = new ConcurrentHashMap<>();
    /** 该屏上一帧用于到期判定的视频时间（场景态清理基线） */
    private static final Map<BlockPos, Long> LAST_VIDEO_TIME = new ConcurrentHashMap<>();
    /** 每屏一份异常日志聚合器（无异常帧不产出日志；随清屏/断线重置） */
    private static final Map<BlockPos, AnomalyLog> ANOMALY = new ConcurrentHashMap<>();
    /** 每屏上次见到的车道数：仅在变化时记一条几何收口 DEBUG（收口本身每帧按当前几何做） */
    private static final Map<BlockPos, Integer> LAST_LANES = new ConcurrentHashMap<>();
    private static long seqCursor;
    /**
     * 滚动/逆向条目的连续纵向打包器（深度层各一份实例：暂存数组独立，层间互不影响）；
     * 层数=1 时只用第 0 份，单层路径即该实例。
     */
    private static final DanmakuPack[] PACKS = new DanmakuPack[DanmakuDepthLayers.MAX_LAYERS];
    /**
     * 固定槽位分配器（深度层 × TOP/BOTTOM 各一份轮转游标）：有空槽必占空槽，
     * 槽位用尽后在各槽位间轮转分散压叠。
     */
    private static final DanmakuPinSlots[] PIN_TOPS = new DanmakuPinSlots[DanmakuDepthLayers.MAX_LAYERS];
    private static final DanmakuPinSlots[] PIN_BOTTOMS = new DanmakuPinSlots[DanmakuDepthLayers.MAX_LAYERS];
    private static boolean clearHookInstalled;

    static {
        for (int layer = 0; layer < DanmakuDepthLayers.MAX_LAYERS; layer++) {
            PACKS[layer] = new DanmakuPack();
            PIN_TOPS[layer] = new DanmakuPinSlots();
            PIN_BOTTOMS[layer] = new DanmakuPinSlots();
        }
    }

    /**
     * 每屏场景态：各深度层一份在屏条目列表 + 轮转分配游标。
     * 层列表按深度层号索引、按需增长；层数下调时超出层的条目随列表一起丢弃（不再绘制也不计入容量闸）。
     */
    private static final class DepthScene {

        private final List<List<Active>> layers = new ArrayList<>();
        /** 轮转分配游标：第 n 个新条目落到第 (n mod 层数) 层 */
        private long cursor;

        /** 指定深度层的在屏条目列表（不存在则新建） */
        List<Active> layer(int index) {
            while (layers.size() <= index) layers.add(new ArrayList<>());
            return layers.get(index);
        }

        /** 层数收口：丢弃超出当前层数的在屏条目 */
        void truncate(int depth) {
            while (layers.size() > depth) layers.remove(layers.size() - 1);
        }

        /** 全部深度层的在屏条目总数（容量闸与异常日志口径） */
        int total() {
            int sum = 0;
            for (List<Active> list : layers) sum += list.size();
            return sum;
        }

        boolean isEmpty() {
            for (List<Active> list : layers) {
                if (!list.isEmpty()) return false;
            }
            return true;
        }
    }

    private DanmakuWorldLayer() {}

    /**
     * 渲染帧入口：消费到期条目，按深度层轮转分配后逐层绘制活动弹幕。
     * 调用方保证已处于屏幕面局部坐标（translate+rotateToFacing 之后、popPose 之前）。
     *
     * <p>深度分层（{@link DanmakuDepthLayers}）：新条目按轮转落到各层，每层各自持打包器与固定槽位
     * 分配器、各自执行原有的连续纵向打包与碰撞判据（同层零相交不变量不变）；同屏上限按层数等比放大；
     * 绘制时逐层沿屏幕面法线朝观察者平移 {@code layerIndex × danmakuDepthSpacing} 格
     * （{@link DanmakuDepthLayers#OBSERVER_Z_SIGN}），层数=1 时不产生任何平移。
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
        // 深度分层参数（层数=1 时恒第 0 层且平移量为 0）
        int depth = DanmakuDepthLayers.layers(config.danmakuDepthLayers.get());
        double depthSpacing = DanmakuDepthLayers.spacing(config.danmakuDepthSpacing.get());

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
        DepthScene scene = ACTIVE.computeIfAbsent(pos, k -> new DepthScene());
        // 层数下调时丢弃超出层的在屏条目（不再绘制，也不计入容量闸）
        scene.truncate(depth);

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
        // 允许压叠：开关开启，或密度档位本身就是「重叠」（该档位语义即"不丢视频弹幕"，与开关同义）
        boolean allowOverlap = config.danmakuAllowOverlap.get()
            || config.danmakuDensity.get() == ClientConfig.DanmakuDensity.OVERLAP;
        // 尺寸变化失效：几何每帧重算（无按屏缓存），在屏条目的纵向坐标按本帧几何收口——
        // 滚动/逆向的落点收进 [0, 可用下界 − 条目高]、固定项的槽位收进 [0, 车道数−1]
        int moved = 0;
        for (int layer = 0; layer < depth; layer++) {
            moved += refitActives(scene.layer(layer), lanes);
        }
        Integer lastLanes = LAST_LANES.put(pos, lanes.lanes);
        if (moved > 0 || (lastLanes != null && lastLanes != lanes.lanes)) {
            KazumiLog.danmaku.debug(
                "World layer geometry refit at {}: lanes {} -> {}, bottomLimit {}, clamped {} of {}",
                pos, lastLanes, lanes.lanes, lanes.bottomLimit, moved, scene.total());
        }
        Font font = Minecraft.getInstance().font;
        // 颜色与文字框几何都在准入时一次算好（每帧只读缓存值，不再逐帧合成 ARGB）
        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int alpha = Math.max(0, Math.min(255, Math.round(alphaFactor * 255.0f)));
        int outlineColorBase = ARGB.black(alpha);
        int frameColorBase = ARGB.color(alpha, ROOM_CHAT_COLOR);

        // 容量闸：有效上限 = danmakuScreenCap() × 层数（层数放大的收益靠同步放大上限才落到同屏条数上）
        int screenCap = DanmakuDepthLayers.effectiveScreenCap(config.danmakuScreenCap(), depth);
        int activeTotal = scene.total();
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
            // 轮转分配：本条落到第 (n mod 层数) 层（层数=1 时恒第 0 层），游标按候选条目推进使各层均匀
            int layer = DanmakuDepthLayers.layerFor(scene.cursor, depth);
            scene.cursor++;
            List<Active> actives = scene.layer(layer);
            // 容量闸只拦视频片内条目：社交条目不受上限约束（仍计入在屏数、仍受落点可用性约束）
            // 压叠模式下不设上限：上限先于落点准入，若仍生效则压叠兜底永远轮不到
            if (!allowOverlap && capBlocks(entry.source(), activeTotal, screenCap)) {
                capped++;
                continue;
            }
            FormattedCharSequence text = entryText(entry);
            float scalePercent = Math.max(10, entry.fontSizePercent())
                / (float) DanmakuEntry.FONT_SIZE_STANDARD;
            // 单条缩放按自身字号算并钳制到单条车道高以内：超大字号不越出固定槽位、也不多占纵向空间
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
                allowOverlap, layer);
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
            activeTotal++;
            accepted++;
        }
        // 每帧异常才输出：首个异常帧一条明细，其后每 5 秒至多一条累计行；无异常帧完全静默
        AnomalyLog.Report report = ANOMALY.computeIfAbsent(pos, k -> new AnomalyLog())
            .submit(clocks.monoMs(), due.size(), accepted, noLane, capped, evicted, forced, forcedVideo,
                skipped);
        if (report != null) logAnomalies("World layer", pos, report, activeTotal);

        // 到期清除逐层执行（各层寿命只由条目自身时钟决定，层间无耦合）
        boolean anyActive = false;
        for (int layer = 0; layer < depth; layer++) {
            List<Active> actives = scene.layer(layer);
            for (int i = actives.size() - 1; i >= 0; i--) {
                Active active = actives.get(i);
                if (clocks.of(active.entry().source()) - active.startMs() >= lifetimeMs(active)) {
                    actives.remove(i);
                }
            }
            anyActive |= !actives.isEmpty();
        }
        if (!anyActive) {
            ACTIVE.remove(pos);
            return;
        }

        // 逐层绘制：层序即远→近，同层内仍是「滚动带 → 固定带」的提交序
        for (int layer = 0; layer < depth; layer++) {
            List<Active> actives = scene.layer(layer);
            if (actives.isEmpty()) continue;
            poseStack.pushPose();
            // 深度平移取世界单位（在 scale 之前施加，否则会被像素域↔世界域换算因子缩放）：
            // 沿屏幕面法线朝观察者 = 本地 −z（见 DanmakuDepthLayers.OBSERVER_Z_SIGN）
            float layerZ = DanmakuDepthLayers.layerOffsetZ(layer, depthSpacing);
            if (layerZ != 0.0f) {
                poseStack.translate(0.0f, 0.0f, layerZ);
            }
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

    /**
     * 单条带的提交：滚动/逆向条目按连续落点提交（落点自显示带上缘起找位，占位高为条目自身文本框高）；
     * TOP 自顶部向下、BOTTOM 自底部向上（槽位 0 最靠下）。
     */
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
            if (outsideDisplay(x, yTop, drawnWPx, activeHeight(active, lanes), halfWPx, lanes)) continue;
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
            // 字形行顶：固定项在槽位内垂直居中，滚动/逆向条目的文本框上缘即落点（缩放后都不越出占用区）
            float localTextTop = textTopLocal(active, lanes, q);

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

    /**
     * 条目落点纵坐标（y 向为下缘侧）：滚动/逆向条目用准入时定下的连续落点，
     * 固定项自显示带（居中于屏幕面）上/下缘按车道高铺开。
     */
    private static float placementYTop(Active active, LaneGeometry lanes) {
        return -lanes.areaH / 2.0f + bandTop(active, lanes);
    }

    /** 条目所在的带内纵坐标（0=显示带上缘、向下为正）：固定项读槽位、滚动/逆向读连续落点 */
    private static float bandTop(Active active, LaneGeometry lanes) {
        return switch (active.mode()) {
            case TOP -> lanes.topOf(active.slot());
            case BOTTOM -> lanes.bottomOf(active.slot());
            default -> active.top();
        };
    }

    /** 条目占用的纵向高度（显示区像素）：固定项一条槽位高、滚动/逆向为自身文本框高 */
    private static float activeHeight(Active active, LaneGeometry lanes) {
        return switch (active.mode()) {
            case TOP, BOTTOM -> lanes.laneH;
            default -> active.height();
        };
    }

    /** 条目字形行顶（条目局部域）：固定项在槽位内居中，滚动/逆向即文本框上缘（局部 0） */
    private static float textTopLocal(Active active, LaneGeometry lanes, float q) {
        return switch (active.mode()) {
            case TOP, BOTTOM -> textTopLocal(q, lanes.laneH);
            default -> 0.0f;
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
                int slot = clampLane(active.slot(), lanes.lanes);
                if (slot == active.slot()) continue;
                actives.set(i, active.withSlot(slot));
            }
            moved++;
        }
        return moved;
    }

    /** 行程进度 p∈[0,1]：按条目自身时钟推进（片内弹幕暂停即冻结；固定项 travelMs=0 不参与行程） */
    private static float progress(Active active, Clocks clocks) {
        int travelMs = active.travelMs();
        if (travelMs <= 0) return 0.0f;
        return (clocks.of(active.entry().source()) - active.startMs()) / (float) travelMs;
    }

    /**
     * 固定项字形在槽位内的垂直居中偏移（条目局部域，即 {@code submitText}/{@code prepareText} 的行顶 y）：
     * 行高 9q 与槽位高 laneH 的差值取半后折算回局部域，故条目按自身缩放放大后仍居中于本槽位、
     * 不越出槽位上下缘（滚动/逆向条目的行顶即落点上缘，局部偏移为 0）。
     * 两层共用同一实现（HUD 层的 {@code inkBounds} 与文字绘制同取本值）。
     */
    static float textTopLocal(float q, float laneH) {
        return (laneH - GLYPH_HEIGHT_PX * q) / (2.0f * q);
    }

    /** 整条落在显示区之外（横向按屏幕面宽度、纵向按显示带）即不提交：按绘制外接框求交 */
    private static boolean outsideDisplay(float x, float yTop, float drawnWPx, float height,
                                          float halfWPx, LaneGeometry lanes) {
        float half = lanes.areaH / 2.0f;
        return x >= halfWPx || x + drawnWPx <= -halfWPx
            || yTop >= half || yTop + height <= -half;
    }

    /** 文字实际绘制外接框（条目局部域，以 y=0 为基准）：取字形四边形真实边界，空文本返回 null */
    private static ScreenRectangle inkBounds(Font font, FormattedCharSequence text, float localTextTop,
                                             int color, boolean dropShadow) {
        return font.prepareText(text, 0.0f, localTextTop, color, dropShadow, false, 0).bounds();
    }

    /** 跳变帧清理：|Δ| 超过 Store 的 seek 阈值即清空该屏全部深度层的活动弹幕（首帧只记基线） */
    public static boolean clearOnSeek(BlockPos pos, long currentVideoTimeMs) {
        Long previous = LAST_VIDEO_TIME.put(pos, currentVideoTimeMs);
        if (previous == null) return false;
        if (Math.abs(currentVideoTimeMs - previous) <= ClientDanmakuStore.SEEK_DETECT_MS) return false;
        DepthScene removed = ACTIVE.remove(pos);
        return removed != null && !removed.isEmpty();
    }

    /**
     * 单条准入：固定项占用感知地分配槽位，滚动/逆向条目交给 {@link DanmakuPack} 求连续纵向落点。
     *
     * <p>社交条目永不丢弃：抢占腾位时会从 actives 就地移除被抢占条目，仍无落点则被迫纵向压叠入屏。
     *
     * @param visual       准入时算好的绘制参数（文本/基础宽度/缩放/配色/文字框外接框）
     * @param actives      本条所属深度层的在屏条目（层间互不参与对方的碰撞判据）
     * @param lanes        本帧车道几何（车道高与车道数随字号缩放，可用下界随底部 UI 安全区）
     * @param allowOverlap danmakuAllowOverlap：开启后视频片内条目无落点时压叠上屏而非丢弃
     * @param layer        本条所属深度层号（取该层自己的打包器与固定槽位游标）
     * @return 准入结果；active 为 null 表示丢弃该条
     */
    private static Admission admit(DanmakuEntry entry, DanmakuMode mode, Visual visual,
                                   List<Active> actives, Clocks clocks, long travelMs, float halfWPx,
                                   LaneGeometry lanes, boolean allowOverlap, int layer) {
        long seq = seqCursor++;
        long startMs = clocks.of(entry.source());
        int pinSlots = lanes.pinSlots();
        return switch (mode) {
            case TOP -> pinAdmit(entry, visual, actives, DanmakuMode.TOP, seq, startMs, pinSlots,
                allowOverlap, layer);
            case BOTTOM -> pinAdmit(entry, visual, actives, DanmakuMode.BOTTOM, seq, startMs, pinSlots,
                allowOverlap, layer);
            case SCROLL, REVERSE -> {
                // 占位宽度与文本框高都按条目实际缩放折算（与 HUD 侧同一口径）
                float drawnWPx = visual.textWPx() * visual.scalePercent();
                float height = GLYPH_HEIGHT_PX * visual.scalePercent();
                DanmakuPack.Pick pick = PACKS[layer].place(actives, mode, entry.source(), drawnWPx,
                    height, visual.scalePercent(), 2 * halfWPx, lanes.bottomLimit, travelMs,
                    clocks.monoMs(), clocks.videoMs(), allowOverlap);
                if (pick.top() < 0.0f) yield Admission.DROPPED;
                Active victim = pick.evictIndex() >= 0 ? actives.remove(pick.evictIndex()) : null;
                yield new Admission(new Active(entry, visual, startMs, seq, mode, (int) travelMs, -1,
                    pick.top(), height), victim, pick.forced());
            }
            case ADVANCED -> Admission.DROPPED;
        };
    }

    /**
     * 固定项准入：槽位数 = 车道数（由显示带高与车道高推导，见 {@link LaneGeometry#pinSlots()}）。
     * 有空槽必占空槽；槽位用尽后社交条目恒压叠、视频片内条目仅在 danmakuAllowOverlap 开启时压叠，
     * 压叠槽位由 {@link DanmakuPinSlots} 按轮转游标在各槽位间分散（不再固定挑「最接近释放」的同一槽）；
     * 无槽可用（槽位数为 0）时丢弃。
     */
    private static Admission pinAdmit(DanmakuEntry entry, Visual visual, List<Active> actives,
                                      DanmakuMode pinMode, long seq, long startMs, int pinSlots,
                                      boolean allowOverlap, int layer) {
        boolean[] used = pinUsage(actives, pinMode, pinSlots);
        DanmakuPinSlots.Pick pick = pinAllocator(pinMode, layer).allocate(used);
        if (pick.slot() < 0) return Admission.DROPPED;
        if (pick.forced() && isVideoDanmaku(entry.source()) && !allowOverlap) return Admission.DROPPED;
        return new Admission(new Active(entry, visual, startMs, seq, pinMode, 0, pick.slot(), 0.0f,
            0.0f), null, pick.forced());
    }

    /**
     * 固定槽位占用情况（长度 = 本帧槽位数）：被在途条目占用的槽位不可复用；
     * 越界槽位号（几何缩水后尚未收口）不计入占用，与 {@link #clampLane} 同口径。
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

    /**
     * 固定槽位分配器（TOP/BOTTOM × 深度层各一份游标；各渲染层与各深度层各持自己的实例，
     * 故同输入逐帧同输出）
     */
    private static DanmakuPinSlots pinAllocator(DanmakuMode mode, int layer) {
        return mode == DanmakuMode.TOP ? PIN_TOPS[layer] : PIN_BOTTOMS[layer];
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
        for (int layer = 0; layer < DanmakuDepthLayers.MAX_LAYERS; layer++) {
            PIN_TOPS[layer].reset();
            PIN_BOTTOMS[layer].reset();
        }
    }

    /** 安装 Store 清屏回调（懒注册一次）：换集/停止时立即释放该屏全部深度层的在途弹幕与场景态基线 */
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
