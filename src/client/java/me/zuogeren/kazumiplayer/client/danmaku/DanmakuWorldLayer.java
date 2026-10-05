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
 * <p>每帧从 {@link ClientDanmakuStore#pollDue} 消费该屏到期条目：SCROLL 右进左出并按车道轮转分配，
 * 车道全占用时退让给最早登记的在途弹幕所在车道；TOP/BOTTOM 各自占独立槽位居中驻留
 * {@link #PIN_HOLD_MS}，槽位用尽即丢弃（不与滚动项抢道）。行程由本地 MonoClock 驱动：
 * 即时项出队即起跑，暂停/seek 不影响互发弹幕的社交语义；片内项由 Store 按解码器钟判定到期。
 *
 * <p>来源差异：房间互发（{@link DanmakuSource#ROOM_CHAT}）按「昵称：内容」组装并染
 * {@link #ROOM_CHAT_COLOR} 金色、外描一个矩形文字框；B 站弹幕按包内自身颜色渲染、不画框。
 * 描边与文字框的 alpha 一律由 danmakuOpacity 控制（ARGB 组合不改 RGB）。
 *
 * <p>字号→世界换算：先按屏幕世界高度与显示区域占比定基准车道世界高，再 poseStack.scale(s) 且
 * s = 车道基准倍率 × danmakuFontScale × 当前最大 fontSizePercent/100；基准倍率保证 100% 条目
 * 恰好填满车道高度，故字号百分比与世界/全屏两层的像素观感一致。车道跨客户端确定性让位于
 * 架构一致性——「落在第几车道」是装饰性差异。
 *
 * <p>描边走 {@code SubmitNodeCollector.submitText} 的 outlineColor 参数（26.1.2 mojmap
 * SubmitNodeStorage L58-L72 十参签名，末位 outlineColor）；文字框走
 * {@code submitCustomGeometry} + 1x1 白纹理实色矩形（同 VideoScreenRenderer 的 fillBar 先例）。
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
    /** 房间互发弹幕定色（金色）；alpha 仍由 danmakuOpacity 决定 */
    private static final int ROOM_CHAT_COLOR = 0xFFD700;
    /** 房间互发弹幕文字框内边距（像素，绘制空间） */
    private static final float FRAME_PAD_X = 2.0f;
    private static final float FRAME_PAD_TOP = 3.0f;
    private static final float FRAME_PAD_BOTTOM = 2.0f;
    /** 文字框深度：略在文字之后，避免与文字面 z-fighting */
    private static final float FRAME_Z = -0.01f;
    /** 文字框实色矩形纹理（运行期注册的 1x1 纯白，专用 id 不与进度条纹理重名） */
    private static final Identifier FRAME_TEX =
        Identifier.fromNamespaceAndPath("kazumiplayer", "danmaku_frame_white");
    private static boolean frameTexRegistered;

    private record Active(DanmakuEntry entry, FormattedCharSequence text, long startMono,
                          long seq, DanmakuMode mode, int travelMs, int lane) {}

    private static final Map<BlockPos, List<Active>> ACTIVE = new ConcurrentHashMap<>();
    /** 该屏上一帧用于到期判定的视频时间（场景态清理基线） */
    private static final Map<BlockPos, Long> LAST_VIDEO_TIME = new ConcurrentHashMap<>();
    private static long seqCursor;
    /** 滚动车道轮转游标：连续发言严格分散到不同车道（车道归属是装饰性差异，轮转最稳） */
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

        int maxOnScreen = config.danmakuMaxOnScreen.get();
        int accepted = 0;
        for (DanmakuEntry entry : due) {
            if (!isVisible(entry, config)) continue;
            if (actives.size() >= maxOnScreen) {
                KazumiLog.danmaku.debug("World danmaku dropped at {}: on-screen cap {} reached (due {})",
                    pos, maxOnScreen, due.size());
                break;
            }
            Active active = admit(entry, actives, now, travelMs);
            if (active == null) continue;
            actives.add(active);
            accepted++;
        }
        if (!due.isEmpty()) {
            KazumiLog.danmaku.debug("World layer consumed {} due at {} (admitted {}, active {})",
                due.size(), pos, accepted, actives.size());
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

        // 车道几何按 100% 基准计算（条目的字号不改变他人位置与大小）
        float laneWorldH = 2 * halfH * config.danmakuAreaRatio.get().floatValue() / LANE_COUNT;
        float scale = laneWorldH / (GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR)
            * config.danmakuFontScale.get().floatValue();
        float alphaFactor = config.danmakuOpacity.get().floatValue();
        boolean outline = config.danmakuOutline.get();
        int outlineColor = ARGB.black(Math.round(alphaFactor * 255.0f));
        int frameColor = ARGB.color(Math.round(alphaFactor * 255.0f), ROOM_CHAT_COLOR);

        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        // 屏幕面局部坐标系相对观察者为 XY 双反（视频 quad 以 U/V 双翻转补偿，见 VideoScreenRenderer UV 注释），
        // 文字层叠加同轴 Z180 真旋转对消——修正水平反向与上下倒置；det=+1 不触发背面剔除
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));

        Font font = Minecraft.getInstance().font;
        // Z180 旋转后绘制系原点在屏幕中心：可见窗口为 x ∈ [−halfWPx, +halfWPx]、y ∈ [−halfHPx, +halfHPx]
        float halfWPx = halfW / scale;
        float halfHPx = halfH / scale;
        float laneHPx = GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR;
        // 固定槽位把滚动区整体下压，避免顶部弹幕与第一滚动车道重叠
        int pinRows = Math.max(usedSlotCount(actives, DanmakuMode.TOP),
            usedSlotCount(actives, DanmakuMode.BOTTOM));
        float rowShiftPx = pinRows * laneHPx;

        for (Active active : actives) {
            int color = entryColor(active.entry(), alphaFactor);
            float textWPx = font.width(active.text());
            float xPx;
            float yTopPx;
            switch (active.mode()) {
                case TOP -> {
                    xPx = -textWPx / 2.0f;
                    yTopPx = -halfHPx + active.lane() * laneHPx + 1.0f;
                }
                case BOTTOM -> {
                    xPx = -textWPx / 2.0f;
                    yTopPx = -halfHPx - (active.lane() + 1) * laneHPx + 1.0f;
                }
                default -> {
                    float progress = (now - active.startMono()) / (float) active.travelMs();
                    // 右进左出：p=0 时文字头贴右缘（整体藏于右缘外），p=1 时文字尾贴左缘滑出
                    xPx = halfWPx - progress * (2 * halfWPx + textWPx);
                    yTopPx = -halfHPx + rowShiftPx + active.lane() * laneHPx + 1.0f;
                }
            }
            // 按条目各自缩放：比例自入场固定（q_i 只由该条目 fontSizePercent 决定，不随在屏集合变化），
            // 大字号条目在自己的车道内放大并可轻微溢出，但不改变其他条目的位置与大小
            float entryScale = config.danmakuFontScale.get().floatValue()
                * Math.max(10, active.entry().fontSizePercent()) / (float) DanmakuEntry.FONT_SIZE_STANDARD;
            poseStack.pushPose();
            poseStack.translate(xPx, yTopPx, 0.0f);
            poseStack.scale(entryScale, entryScale, 1.0f);
            if (active.entry().source() == DanmakuSource.ROOM_CHAT) {
                submitBox(collector, poseStack,
                    -FRAME_PAD_X, textWPx + FRAME_PAD_X,
                    -FRAME_PAD_TOP, laneHPx + FRAME_PAD_BOTTOM,
                    frameColor);
            }
            collector.submitText(poseStack, 0.0f, TEXT_BASELINE_OFFSET_PX, active.text(),
                !outline, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, color, 0, outline ? outlineColor : 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** 跳变帧清理：|Δ| 超过 Store 的 seek 阈值即清空该屏活动弹幕（首帧只记基线） */
    public static boolean clearOnSeek(BlockPos pos, long currentVideoTimeMs) {
        Long previous = LAST_VIDEO_TIME.put(pos, currentVideoTimeMs);
        if (previous == null) return false;
        if (Math.abs(currentVideoTimeMs - previous) <= ClientDanmakuStore.SEEK_DETECT_MS) return false;
        return ACTIVE.remove(pos) != null;
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
     * （拥塞优先保上屏，上屏条数由 danmakuMaxOnScreen 单点约束，重叠只可能发生在满屏场景）。
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
        return ARGB.color(Math.round(alphaFactor * 255.0f), rgb & 0xFFFFFF);
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

    /** 实色矩形（文字框）：沿用 VideoScreenRenderer.fillBar 的顶点与材质写法 */
    private static void submitBox(SubmitNodeCollector collector, PoseStack poseStack,
                                  float xMin, float xMax, float yMin, float yMax, int color) {
        ensureFrameTexture();
        RenderType type = RenderTypes.entityCutout(FRAME_TEX);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) ->
            fillRect(buffer, pose, xMin, xMax, yMin, yMax, FRAME_Z, color));
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
