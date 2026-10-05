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
 * <p>车道准入是占用感知的：该车道每条在途弹幕的已推进像素须 ≥ max(新条文本宽, 该条文本宽) +
 * {@link #MIN_GAP_PX} 才允许新条入轨（按行程比例换算成时间提前量），没有满足条件的车道就丢弃该条
 * 并计入 DEBUG 统计，不再轮转硬塞；占位宽度取两者的较大值，保证更宽的追随弹幕（行程更快）在最坏
 * 时刻也不会追上前车。TOP/BOTTOM 槽位同样按驻留时长占位，无空闲槽即丢弃。
 *
 * <p>模式：SCROLL 右进左出、REVERSE 左进右出（镜像）；TOP/BOTTOM 居中驻留 {@link #PIN_HOLD_MS}，
 * 不受 speedMultiplier 影响。行程由本地 MonoClock 驱动：即时项出队即起跑，暂停/seek 不影响
 * 互发弹幕的社交语义；片内项由 Store 按解码器钟判定到期。
 *
 * <p>来源差异：房间互发（{@link DanmakuSource#ROOM_CHAT}）按「昵称：内容」组装并染
 * {@link #ROOM_CHAT_COLOR} 金色、外描一个空心矩形文字框；B 站弹幕按包内自身颜色渲染、不画框。
 * 文字框与描边的 alpha 一律由 danmakuOpacity 控制（ARGB 组合不改 RGB）。
 *
 * <p>字号→世界换算：车道几何恒按 100% 基准（laneHPx = 9×1.4）计算，字号百分比只作用于条目自身
 * 的 pose 缩放 q_i = danmakuFontScale × fontSizePercent/100，故任一条目上下场都不会改变他人的
 * 位置与大小；q_i 会被钳制到单条带高以内，避免超高条目溢出到相邻车道。
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
    /** 文字基线相对车道顶部的偏移像素 */
    private static final float TEXT_BASELINE_OFFSET_PX = 7.0f;
    /** 顶部/底部项驻留时长（毫秒）：固定模式不受 speedMultiplier 影响 */
    private static final long PIN_HOLD_MS = 4500L;
    /** 车道让位判据的水平间隔（像素）：旧条已推进像素须 ≥ max(新条,旧条)文本宽 + 此值才释放车道 */
    private static final float MIN_GAP_PX = 6.0f;
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 房间互发弹幕文字框内边距与线宽（像素，条目局部绘制空间） */
    private static final float FRAME_PAD_X = 2.0f;
    private static final float FRAME_PAD_TOP = 3.0f;
    private static final float FRAME_PAD_BOTTOM = 2.0f;
    private static final float FRAME_EDGE = 1.0f;
    /** 文字框深度：略在文字之后（文字用 POLYGON_OFFSET 前移），保证框在文字下层 */
    private static final float FRAME_Z = -0.01f;
    /** 文字框实色矩形纹理（运行期注册的 1x1 纯白，专用 id 不与进度条纹理重名） */
    private static final Identifier FRAME_TEX =
        Identifier.fromNamespaceAndPath("kazumiplayer", "danmaku_frame_white");
    private static boolean frameTexRegistered;

    private record Active(DanmakuEntry entry, FormattedCharSequence text, long startMono,
                          long seq, DanmakuMode mode, int travelMs, int lane,
                          float textWPx, float scalePercent) {}

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
        long now = MonoClock.millis();
        long travelMs = scrollTravelMs();
        long videoTimeMs = state.player != null ? state.player.getTimeMs() : 0;

        // 场景态清理先于出队：跳变帧的旧弹幕整批作废
        if (clearOnSeek(pos, videoTimeMs)) {
            KazumiLog.danmaku.debug("World layer cleared active danmaku at {} after seek to {}ms",
                pos, videoTimeMs);
        }

        List<DanmakuEntry> due = ClientDanmakuStore.pollDue(pos, videoTimeMs);
        List<Active> actives = ACTIVE.computeIfAbsent(pos, k -> new ArrayList<>());

        // 车道几何恒按 100% 基准：字号只影响条目自身缩放，不挤压/不位移他人
        float laneWorldH = 2 * halfH * config.danmakuAreaRatio.get().floatValue() / LANE_COUNT;
        float baseScale = laneWorldH / (GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR);
        float halfWPx = halfW / baseScale;
        float halfHPx = halfH / baseScale;
        float laneHPx = GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR;
        float fontScale = config.danmakuFontScale.get().floatValue();
        Font font = Minecraft.getInstance().font;

        int maxOnScreen = config.danmakuMaxOnScreen.get();
        int accepted = 0;
        int noLane = 0;
        int skipped = 0;
        for (DanmakuEntry entry : due) {
            if (entry.mode() == DanmakuMode.ADVANCED) {
                skipped++;
                continue;
            }
            if (!isVisible(entry, config)) continue;
            if (actives.size() >= maxOnScreen) {
                KazumiLog.danmaku.debug("World danmaku dropped at {}: on-screen cap {} reached (due {})",
                    pos, maxOnScreen, due.size());
                break;
            }
            float textWPx = font.width(entryText(entry));
            float scalePercent = Math.max(10, entry.fontSizePercent()) / 100.0f;
            // 单条占用高度按实际缩放算并钳制到带高以内，避免超大字号溢出压到相邻车道
            float bandH = laneHPx;
            float entryScale = Math.min(fontScale * scalePercent, bandH / GLYPH_HEIGHT_PX);
            Active active = admit(entry, actives, now, travelMs, textWPx, entryScale, halfWPx, laneHPx);
            if (active == null) {
                noLane++;
                continue;
            }
            actives.add(active);
            accepted++;
        }
        if (!due.isEmpty() || noLane > 0 || skipped > 0) {
            KazumiLog.danmaku.debug(
                "World layer consumed {} due at {} (admitted {}, no-free-lane {}, advanced-skipped {}, active {})",
                due.size(), pos, accepted, noLane, skipped, actives.size());
        }

        for (int i = actives.size() - 1; i >= 0; i--) {
            Active active = actives.get(i);
            if (now - active.startMono() >= lifetimeMs(active)) {
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
        drawBand(collector, poseStack, actives, DanmakuMode.SCROLL, now, halfWPx, halfHPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        drawBand(collector, poseStack, actives, DanmakuMode.REVERSE, now, halfWPx, halfHPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        drawBand(collector, poseStack, actives, DanmakuMode.TOP, now, halfWPx, halfHPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        drawBand(collector, poseStack, actives, DanmakuMode.BOTTOM, now, halfWPx, halfHPx, laneHPx,
            alphaFactor, outline, outlineColor, frameColor);
        poseStack.popPose();
    }

    /** 单条带的提交：滚动带起点恒为显示区顶部；TOP 自顶部向下、BOTTOM 自底部向上 */
    private static void drawBand(SubmitNodeCollector collector, PoseStack poseStack, List<Active> actives,
                                 DanmakuMode band, long now, float halfWPx, float halfHPx, float laneHPx,
                                 float alphaFactor, boolean outline, int outlineColor, int frameColor) {
        for (Active active : actives) {
            if (active.mode() != band) continue;
            float q = active.scalePercent();
            float textWPx = active.textWPx();
            float xPx;
            float yTopPx;
            switch (band) {
                case TOP -> {
                    xPx = -textWPx / 2.0f;
                    yTopPx = -halfHPx + active.lane() * laneHPx + 1.0f;
                }
                case BOTTOM -> {
                    xPx = -textWPx / 2.0f;
                    yTopPx = -halfHPx - (active.lane() + 1) * laneHPx + 1.0f;
                }
                case REVERSE -> {
                    float progress = (now - active.startMono()) / (float) active.travelMs();
                    // 左进右出：SCROLL 的镜像
                    xPx = -halfWPx - textWPx + progress * (2 * halfWPx + textWPx);
                    yTopPx = -halfHPx + active.lane() * laneHPx + 1.0f;
                }
                default -> {
                    float progress = (now - active.startMono()) / (float) active.travelMs();
                    // 右进左出：p=0 时文字头贴右缘（整体藏于右缘外），p=1 时文字尾贴左缘滑出
                    xPx = halfWPx - progress * (2 * halfWPx + textWPx);
                    yTopPx = -halfHPx + active.lane() * laneHPx + 1.0f;
                }
            }
            // 整条移出显示区即不再提交（滚动/逆向滚动出界后无可见部分）
            if (xPx > halfWPx || xPx + textWPx < -halfWPx) continue;

            poseStack.pushPose();
            poseStack.translate(xPx, yTopPx, 0.0f);
            poseStack.scale(q, q, 1.0f);
            if (active.entry().source() == DanmakuSource.ROOM_CHAT) {
                submitFrame(collector, poseStack,
                    -FRAME_PAD_X, textWPx + FRAME_PAD_X,
                    -FRAME_PAD_TOP, laneHPx + FRAME_PAD_BOTTOM, frameColor);
            }
            collector.submitText(poseStack, 0.0f, TEXT_BASELINE_OFFSET_PX, active.text(),
                !outline, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, entryColor(active.entry(), alphaFactor), 0,
                outline ? outlineColor : 0);
            poseStack.popPose();
        }
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
     * @return 已登记的 Active；无可用车道/槽位返回 null（调用侧丢弃并计入 DEBUG）
     */
    private static Active admit(DanmakuEntry entry, List<Active> actives, long now, long travelMs,
                                float textWPx, float entryScale, float halfWPx, float laneHPx) {
        long seq = seqCursor++;
        return switch (entry.mode()) {
            case TOP -> {
                int slot = claimPinSlot(actives, DanmakuMode.TOP, now);
                yield slot < 0 ? null : new Active(entry, entryText(entry), now, seq,
                    DanmakuMode.TOP, 0, slot, textWPx, entryScale);
            }
            case BOTTOM -> {
                int slot = claimPinSlot(actives, DanmakuMode.BOTTOM, now);
                yield slot < 0 ? null : new Active(entry, entryText(entry), now, seq,
                    DanmakuMode.BOTTOM, 0, slot, textWPx, entryScale);
            }
            case SCROLL, REVERSE -> {
                // 占位宽度按条目实际缩放后的绘制宽度计（与 HUD 侧一致）
                int lane = freeLane(actives, entry.mode(), textWPx * entryScale, halfWPx, now, travelMs);
                yield lane < 0 ? null : new Active(entry, entryText(entry), now, seq,
                    entry.mode(), (int) travelMs, lane, textWPx, entryScale);
            }
            default -> null;
        };
    }

    /**
     * 车道准入判据：该车道每条在途弹幕的已推进像素须 ≥ max(新条绘制宽, 该条绘制宽) + {@link #MIN_GAP_PX}。
     * 宽度一律按条目实际缩放后的绘制宽度计（textWPx × scalePercent），与 HUD 侧同一口径；
     * 占位宽度取两者较大值——更宽的弹幕行程更快会追上较窄的前车，只用前车宽度算提前量会在最坏时刻追尾；
     * 已推进像素按行程比例换算成时间提前量（毫秒向上取整，让位不早于理论时刻），与画面时间无关（本地钟驱动）。
     */
    private static boolean laneReleased(List<Active> actives, DanmakuMode mode, int lane, float newWPx,
                                        float halfWPx, long now, long travelMs) {
        for (Active active : actives) {
            if (active.mode() != mode || active.lane() != lane) continue;
            float widest = Math.max(newWPx, active.textWPx() * active.scalePercent());
            double span = 2 * halfWPx + widest;
            long advance = (long) Math.ceil(travelMs * Math.min(1.0, (widest + MIN_GAP_PX) / span));
            if (now - active.startMono() < advance) return false;
        }
        return true;
    }

    /** 固定槽位占用感知：驻留未满的槽位不可复用；返回可用槽位下标，无则 -1 */
    private static int claimPinSlot(List<Active> actives, DanmakuMode mode, long now) {
        long[] busyUntil = new long[PIN_SLOT_COUNT];
        for (Active active : actives) {
            if (active.mode() != mode || active.lane() >= PIN_SLOT_COUNT) continue;
            busyUntil[active.lane()] = Math.max(busyUntil[active.lane()], active.startMono() + PIN_HOLD_MS);
        }
        for (int offset = 0; offset < PIN_SLOT_COUNT; offset++) {
            int slot = Math.floorMod(laneCursor + offset, PIN_SLOT_COUNT);
            if (busyUntil[slot] <= now) {
                laneCursor = Math.floorMod(slot + 1, PIN_SLOT_COUNT);
                return slot;
            }
        }
        return -1;
    }

    /** 从轮转游标起取第一条已让出空间的车道（newWPx 为新条绘制宽）；全部车道都在占用期返回 -1 */
    private static int freeLane(List<Active> actives, DanmakuMode mode, float newWPx, float halfWPx,
                                long now, long travelMs) {
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int lane = Math.floorMod(laneCursor + offset, LANE_COUNT);
            if (laneReleased(actives, mode, lane, newWPx, halfWPx, now, travelMs)) {
                laneCursor = Math.floorMod(lane + 1, LANE_COUNT);
                return lane;
            }
        }
        return -1;
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

    /** 来源与模式开关过滤：关闭的来源/模式在出队后即丢弃，不占容量闸；REVERSE 跟随滚动开关 */
    private static boolean isVisible(DanmakuEntry entry, ClientConfig config) {
        boolean sourceOn = switch (entry.source()) {
            case BILIBILI_VIDEO -> config.danmakuBilibiliVideo.get();
            case BILIBILI_LIVE -> config.danmakuBilibiliLive.get();
            case ROOM_CHAT -> config.danmakuRoomChat.get();
        };
        boolean modeOn = switch (entry.mode()) {
            case SCROLL, REVERSE -> config.danmakuShowScroll.get();
            case TOP -> config.danmakuShowTop.get();
            case BOTTOM -> config.danmakuShowBottom.get();
            case ADVANCED -> false;
        };
        return sourceOn && modeOn;
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
                                    float xMin, float xMax, float yMin, float yMax, int color) {
        ensureFrameTexture();
        RenderType type = RenderTypes.entityCutout(FRAME_TEX);
        float e = FRAME_EDGE;
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
