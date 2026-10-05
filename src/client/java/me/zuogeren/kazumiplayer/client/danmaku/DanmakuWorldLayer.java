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
 * <p>字号→世界换算：车道几何恒按 100% 基准（laneHPx = 9×1.4）计算，字号百分比只作用于条目自身
 * 的 pose 缩放 q_i = danmakuFontScale × fontSizePercent/100，故任一条目上下场都不会改变他人的
 * 位置与大小；q_i 会被钳制到单条带高以内，避免超高条目溢出到相邻车道。基准换算因子
 * {@link #baseScale} 按 danmakuScaleWithScreen 取屏幕实际尺寸或固定基准半高——关闭缩放时字号
 * 在世界中恒为默认屏幕（3×2 格）折算的大小，不随屏幕方块尺寸变化（全屏 HUD 层不受该开关影响）。
 *
 * <p>显示区坐标：原点为屏幕面中心，显示带高 = LANE_COUNT×laneHPx（即 danmakuAreaRatio 指定的
 * 居中显示区）、宽 = 屏幕面宽，x 向为 SCROLL 的起跑侧、y 向为下缘侧。条目位置一律按实际绘制宽度（textWPx × q）计算，
 * 位移不随字号缩放；字形按 (laneHPx - 9q)/2 在带内垂直居中，故缩放后仍不越出本带；
 * 整条移出显示区（按绘制外接框与显示区求交，含纵向）即不提交，文字不会飘到屏幕面之外。
 *
 * <p>描边走 {@code SubmitNodeCollector.submitText} 的 outlineColor 参数（26.1.2 mojmap
 * SubmitNodeStorage L58-L72 十参签名，末位 outlineColor）；文字框走
 * {@code submitCustomGeometry} + 1x1 白纹理的四条细边（同 VideoScreenRenderer 的 fillBar 先例）。
 *
 * <p>场景态清理（f10 裁定 A 的渲染侧责任）：Store 内部只压缩过期条目，跳变后的活动弹幕由本层
 * 按「上帧视频时间」自检清理——|Δ| > {@link ClientDanmakuStore#SEEK_DETECT_MS} 时清空该屏 ACTIVE，
 * 避免 seek 后旧弹幕继续飘完行程。
 */
public final class DanmakuWorldLayer {

    /** 滚动车道数（显示区域内的并行轨道） */
    private static final int LANE_COUNT = 5;
    /** 固定模式（顶部/底部）各自可用的居中槽位上限 */
    private static final int PIN_SLOT_COUNT = 3;
    /** 字形行高像素（原版字体 9px）与行距系数 */
    private static final float GLYPH_HEIGHT_PX = 9.0f;
    private static final float LINE_HEIGHT_FACTOR = 1.4f;
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

    /**
     * 在屏条目。
     *
     * @param startMs 该条驱动时钟在准入时刻的取值（社交=单调钟，片内=播放器位置）
     * @param textWPx 条目文本的基础像素宽（未乘 {@code scalePercent}）
     */
    private record Active(DanmakuEntry entry, FormattedCharSequence text, long startMs,
                          long seq, DanmakuMode mode, int travelMs, int lane,
                          float textWPx, float scalePercent) {}

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

        // 车道几何恒按 100% 基准：字号只影响条目自身缩放，不挤压/不位移他人
        float baseScale = baseScale(halfH, config);
        float halfWPx = halfW / baseScale;
        float laneHPx = GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR;
        float fontScale = config.danmakuFontScale.get().floatValue();
        Font font = Minecraft.getInstance().font;

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
            // 单条占用高度按实际缩放算并钳制到带高以内，避免超大字号溢出压到相邻车道
            float entryScale = Math.min(fontScale * scalePercent, laneHPx / GLYPH_HEIGHT_PX);
            float textWPx = font.width(text);
            Admission adm = admit(entry, mode, text, actives, clocks, travelMs, textWPx, entryScale,
                halfWPx, laneHPx);
            if (adm.active() == null) {
                noLane++;
                continue;
            }
            if (adm.evicted() != null) evicted++;
            if (adm.forced()) forced++;
            actives.add(adm.active());
            accepted++;
        }
        if (!due.isEmpty() || noLane > 0 || capped > 0 || evicted > 0 || forced > 0 || skipped > 0) {
            KazumiLog.danmaku.debug(
                "World layer consumed {} due at {} (admitted {}, no-free-lane {}, capped-video {}, evicted-for-social {}, forced-overlap {}, advanced-skipped {}, active {})",
                due.size(), pos, accepted, noLane, capped, evicted, forced, skipped, actives.size());
        }

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

        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int outlineColor = ARGB.black(Math.round(alphaFactor * 255.0f));
        int frameColor = ARGB.color(Math.round(alphaFactor * 255.0f), ROOM_CHAT_COLOR);

        poseStack.pushPose();
        poseStack.scale(baseScale, baseScale, baseScale);
        // 屏幕面局部坐标系相对观察者为 XY 双反（视频 quad 以 U/V 双翻转补偿，见 VideoScreenRenderer UV 注释），
        // 文字层叠加同轴 Z180 真旋转对消——修正水平反向与上下倒置；det=+1 不触发背面剔除
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));

        // 分带提交：滚动带先画，TOP/BOTTOM 后画 → 三条带重叠处固定项压在上层
        drawBand(collector, poseStack, actives, DanmakuMode.SCROLL, clocks, font, halfWPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        drawBand(collector, poseStack, actives, DanmakuMode.REVERSE, clocks, font, halfWPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        drawBand(collector, poseStack, actives, DanmakuMode.TOP, clocks, font, halfWPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        drawBand(collector, poseStack, actives, DanmakuMode.BOTTOM, clocks, font, halfWPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        poseStack.popPose();
    }

    /**
     * 像素域→世界域的基准缩放：车道带高（世界单位）= 屏幕面高 × danmakuAreaRatio，除以五条车道、
     * 再除以单车道像素高。danmakuScaleWithScreen=false 时改用固定基准半高
     * {@link #FIXED_SCALE_BASIS_HALF_H}（默认屏幕尺寸），字号不再随屏幕方块尺寸缩放。
     *
     * @param halfH 屏幕面半高（世界单位/格）
     */
    static float baseScale(float halfH, ClientConfig config) {
        float basisHalfH = config.danmakuScaleWithScreen.get() ? halfH : FIXED_SCALE_BASIS_HALF_H;
        float laneWorldH = 2 * basisHalfH * config.danmakuAreaRatio.get().floatValue() / LANE_COUNT;
        return laneWorldH / (GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR);
    }

    /** 单条带的提交：滚动带起点恒为显示区顶部；TOP 自顶部向下、BOTTOM 自底部向上（slot 0 最靠下） */
    private static void drawBand(SubmitNodeCollector collector, PoseStack poseStack, List<Active> actives,
                                 DanmakuMode band, Clocks clocks, Font font, float halfWPx, float laneHPx,
                                 float alphaFactor, boolean outline, int outlineColor, int frameColor) {
        for (Active active : actives) {
            if (active.mode() != band) continue;
            float q = active.scalePercent();
            float drawnWPx = active.textWPx() * q;
            Placement place = placementOf(active, clocks, halfWPx, laneHPx);
            // 整条移出显示区即不再提交（按实际绘制外接框求交，避免文字飘到屏幕面之外）
            if (outsideDisplay(place, drawnWPx, laneHPx, halfWPx)) continue;
            // 字形在带内垂直居中：字号缩放后仍不越出本带与显示区上下缘
            float localTextTop = textTopInBand(q, laneHPx) / q;
            int color = entryColor(active.entry(), alphaFactor);

            poseStack.pushPose();
            poseStack.translate(place.x(), place.yTop(), 0.0f);
            poseStack.scale(q, q, 1.0f);
            if (active.entry().source() == DanmakuSource.ROOM_CHAT) {
                ScreenRectangle ink = inkBounds(font, active, localTextTop, color, !outline);
                if (ink != null) {
                    float[] box = frameBox(ink, q);
                    submitFrame(collector, poseStack, box[0], box[2], box[1], box[3],
                        FRAME_EDGE / q, frameColor);
                }
            }
            collector.submitText(poseStack, 0.0f, localTextTop, active.text(),
                !outline, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, color, 0,
                outline ? outlineColor : 0);
            poseStack.popPose();
        }
    }

    /** 条目落点（屏幕面局部像素坐标：原点为面中心，x 向为 SCROLL 起跑侧、y 向为下缘侧） */
    private record Placement(float x, float yTop) {}

    /** 显示带半高（像素）：danmakuAreaRatio 指定的显示区居中于屏幕面，5 条车道自 -halfBand 铺到 +halfBand */
    private static float halfBand(float laneHPx) {
        return LANE_COUNT * laneHPx / 2.0f;
    }

    /**
     * 条目在显示区内的落点。横向一律按实际绘制宽度（textWPx × q）计算，位移不随字号缩放放大：
     * SCROLL 自 +x 缘进入向左行进、REVERSE 自 -x 缘进入向右行进（互为镜像）；
     * TOP 带自显示区上缘向下、BOTTOM 带自下缘向上，槽位 0 分别最靠上/最靠下。
     */
    private static Placement placementOf(Active active, Clocks clocks, float halfWPx, float laneHPx) {
        float drawnWPx = active.textWPx() * active.scalePercent();
        float half = halfBand(laneHPx);
        float yTop = -half + active.lane() * laneHPx;
        float travel = 2 * halfWPx + drawnWPx;
        float progress = progress(active, clocks);
        return switch (active.mode()) {
            case TOP -> new Placement(-drawnWPx / 2.0f, yTop);
            case BOTTOM -> new Placement(-drawnWPx / 2.0f, half - (active.lane() + 1) * laneHPx);
            case REVERSE -> new Placement(-halfWPx - drawnWPx + progress * travel, yTop);
            default -> new Placement(halfWPx - progress * travel, yTop);
        };
    }

    /** 行程进度 p∈[0,1]：按条目自身时钟推进（片内弹幕暂停即冻结；固定项 travelMs=0 不参与行程） */
    private static float progress(Active active, Clocks clocks) {
        int travelMs = active.travelMs();
        if (travelMs <= 0) return 0.0f;
        return (clocks.of(active.entry().source()) - active.startMs()) / (float) travelMs;
    }

    /** 字形在带内的垂直偏移（显示区像素）：缩放后仍居中于本带 */
    private static float textTopInBand(float q, float laneHPx) {
        return (laneHPx - GLYPH_HEIGHT_PX * q) / 2.0f;
    }

    /** 整条落在显示区之外（横向按屏幕面宽度、纵向按显示带）即不提交：按绘制外接框求交 */
    private static boolean outsideDisplay(Placement place, float drawnWPx, float laneHPx, float halfWPx) {
        float half = halfBand(laneHPx);
        return place.x() >= halfWPx || place.x() + drawnWPx <= -halfWPx
            || place.yTop() >= half || place.yTop() + laneHPx <= -half;
    }

    /** 文字实际绘制外接框（条目局部域）：取字形四边形真实边界（含投影），空文本返回 null */
    private static ScreenRectangle inkBounds(Font font, Active active, float localTextTop,
                                             int color, boolean dropShadow) {
        return font.prepareText(active.text(), 0.0f, localTextTop, color, dropShadow, false, 0).bounds();
    }

    /**
     * 文字框矩形（条目局部域，{xMin, yMin, xMax, yMax}）：由文字实际绘制外接框四边各外扩
     * {@link #FRAME_PAD} 像素——内边距除以 q 是因为局部坐标随后被 q 缩放，屏幕上才恒为 FRAME_PAD。
     */
    private static float[] frameBox(ScreenRectangle ink, float q) {
        float pad = FRAME_PAD / q;
        return new float[] {ink.left() - pad, ink.top() - pad, ink.right() + pad, ink.bottom() + pad};
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
     * @param textWPx      条目文本基础像素宽（未乘 {@code scalePercent}）
     * @param scalePercent 条目 pose 缩放（danmakuFontScale × fontSizePercent/100，钳制到带高以内）
     * @return 准入结果；active 为 null 表示丢弃该条
     */
    private static Admission admit(DanmakuEntry entry, DanmakuMode mode, FormattedCharSequence text,
                                   List<Active> actives, Clocks clocks, long travelMs, float textWPx,
                                   float scalePercent, float halfWPx, float laneHPx) {
        long seq = seqCursor++;
        long startMs = clocks.of(entry.source());
        return switch (mode) {
            case TOP -> pinAdmit(entry, text, actives, clocks, DanmakuMode.TOP, textWPx, scalePercent, seq, startMs);
            case BOTTOM -> pinAdmit(entry, text, actives, clocks, DanmakuMode.BOTTOM, textWPx, scalePercent, seq, startMs);
            case SCROLL, REVERSE -> {
                // 占位宽度按条目实际缩放后的绘制宽度计（与 HUD 侧同判据）
                LanePick pick = pickLane(actives, mode, textWPx * scalePercent, halfWPx, clocks, travelMs,
                    !isVideoDanmaku(entry.source()));
                if (pick.lane() < 0) yield Admission.DROPPED;
                Active victim = pick.evictIndex() >= 0 ? actives.remove(pick.evictIndex()) : null;
                yield new Admission(new Active(entry, text, startMs, seq, mode, (int) travelMs,
                    pick.lane(), textWPx, scalePercent), victim, pick.forced());
            }
            case ADVANCED -> Admission.DROPPED;
        };
    }

    /** 固定项准入：优先空槽；社交条目无空槽时占「最接近释放」的槽位（被迫重叠并计入 forced） */
    private static Admission pinAdmit(DanmakuEntry entry, FormattedCharSequence text, List<Active> actives,
                                      Clocks clocks, DanmakuMode pinMode, float textWPx, float scalePercent,
                                      long seq, long startMs) {
        int slot = firstFreePin(actives, pinMode);
        boolean forced = false;
        if (slot < 0) {
            if (isVideoDanmaku(entry.source())) return Admission.DROPPED;
            slot = oldestPin(actives, pinMode, clocks);
            if (slot < 0) return Admission.DROPPED;
            forced = true;
        }
        return new Admission(new Active(entry, text, startMs, seq, pinMode, 0, slot, textWPx, scalePercent),
            null, forced);
    }

    /** 固定槽位占用感知：被在途条目占用的槽位不可复用；返回可用槽位下标，无则 -1 */
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

    /** 「最接近释放」的固定槽位：占用者中按各自时钟已驻留最久者；无占用返回 -1 */
    private static int oldestPin(List<Active> actives, DanmakuMode mode, Clocks clocks) {
        int slot = -1;
        long bestElapsed = Long.MIN_VALUE;
        for (Active active : actives) {
            if (active.mode() != mode || active.lane() >= PIN_SLOT_COUNT) continue;
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
     * @param social 该条是否为社交条目（房间互发/直播）：可抢占、可被迫重叠
     */
    private static LanePick pickLane(List<Active> actives, DanmakuMode mode, float newWPx, float halfWPx,
                                     Clocks clocks, long travelMs, boolean social) {
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int lane = Math.floorMod(laneCursor + offset, LANE_COUNT);
            if (laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs)) {
                laneCursor = Math.floorMod(lane + 1, LANE_COUNT);
                return new LanePick(lane, -1, false);
            }
        }
        if (!social) return new LanePick(-1, -1, false);
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int lane = Math.floorMod(laneCursor + offset, LANE_COUNT);
            int victim = evictCandidate(actives, mode, lane, clocks);
            if (victim < 0) continue;
            if (!laneReleased(actives, mode, lane, newWPx, halfWPx, clocks, travelMs, victim)) continue;
            laneCursor = Math.floorMod(lane + 1, LANE_COUNT);
            return new LanePick(lane, victim, false);
        }
        int bestLane = -1;
        float mostAdvance = -1.0f;
        for (int lane = 0; lane < LANE_COUNT; lane++) {
            float advance = lastAdvance(actives, mode, lane, halfWPx, clocks, travelMs);
            if (advance > mostAdvance) {
                mostAdvance = advance;
                bestLane = lane;
            }
        }
        if (bestLane < 0) return new LanePick(-1, -1, false);
        laneCursor = Math.floorMod(bestLane + 1, LANE_COUNT);
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
            float widest = Math.max(newWPx, active.textWPx() * active.scalePercent());
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
                newestWidth = active.textWPx() * active.scalePercent();
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
    }

    /** 安装 Store 清屏回调（懒注册一次）：换集/停止时立即释放该屏在途弹幕与场景态基线 */
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

    /** 空心文字框：四条细边各自提交，框内不填充，保持文字下层（FRAME_Z） */
    private static void submitFrame(SubmitNodeCollector collector, PoseStack poseStack,
                                    float xMin, float xMax, float yMin, float yMax, float e, int color) {
        ensureFrameTexture();
        RenderType type = RenderTypes.entityCutout(FRAME_TEX);
        submitEdge(collector, poseStack, type, xMin, xMax, yMin, yMin + e, color);
        submitEdge(collector, poseStack, type, xMin, xMax, yMax - e, yMax, color);
        submitEdge(collector, poseStack, type, xMin, xMin + e, yMin + e, yMax - e, color);
        submitEdge(collector, poseStack, type, xMax - e, xMax, yMin + e, yMax - e, color);
    }

    private static void submitEdge(SubmitNodeCollector collector, PoseStack poseStack, RenderType type,
                                   float xMin, float xMax, float yMin, float yMax, int color) {
        collector.submitCustomGeometry(poseStack, type,
            (pose, buffer) -> fillRect(buffer, pose, xMin, xMax, yMin, yMax, FRAME_Z, color));
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
