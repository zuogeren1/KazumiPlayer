package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderState;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界内弹幕文字层（挂在 VideoScreenRenderer.submit 尾部，屏幕面局部坐标系内绘制）。
 *
 * <p>渲染模型（t2-v1.3 定稿）：每帧从 {@link ClientDanmakuStore#pollDue} 消费该屏到期条目，
 * 出队时增量分配车道，行程由本地 MonoClock 驱动（即时项 displayAt=0 出队即起跑，
 * 暂停/seek 不影响互发弹幕的社交语义）。滚动映射右进左出：
 * p=(now-start)/travel ∈ [0,1)，headX 从画布右缘推进到负文本宽。
 *
 * <p>字号→世界换算：先按屏幕世界高度与显示区域占比定车道世界高
 * laneWorldH = 2·halfH·areaRatio/LANE_COUNT，再 poseStack.scale(s) 且 s = laneWorldH/(9×1.4)
 * （9px 原版字形行高 × 1.4 行距系数），此后坐标单位为像素。车道跨客户端确定性让位于
 * 架构一致性——「落在第几车道」是装饰性差异。
 */
public final class DanmakuWorldLayer {

    /** 车道数（显示区域内的并行滚动轨道） */
    private static final int LANE_COUNT = 5;
    /** 字形行高像素（原版字体 9px）与行距系数 */
    private static final float GLYPH_HEIGHT_PX = 9.0f;
    private static final float LINE_HEIGHT_FACTOR = 1.4f;

    private record Active(DanmakuEntry entry, Component text, long startMono, int lane) {}

    private static final Map<BlockPos, List<Active>> ACTIVE = new ConcurrentHashMap<>();

    private DanmakuWorldLayer() {}

    /**
     * 渲染帧入口：消费到期条目并绘制活动弹幕。
     * 调用方保证已处于屏幕面局部坐标（translate+rotateToFacing 之后、popPose 之前）。
     */
    public static void draw(SubmitNodeCollector collector, com.mojang.blaze3d.vertex.PoseStack poseStack,
                            VideoScreenRenderState state, float halfW, float halfH) {
        var config = ClientConfig.CONFIG;
        if (!config.danmakuEnabled.get()) return;
        // 防双消费：全屏激活期由 DanmakuHudLayer 独占 pollDue 出队（coverage<100 时世界画面短暂无弹幕为既定取舍）
        if (me.zuogeren.kazumiplayer.client.ClientFullscreenState.isActive()) return;

        BlockPos pos = state.blockPos;
        long now = MonoClock.millis();
        long travelMs = instantTravelMs();

        // 消费到期条目（即时项恒到期；片内时间轴项随 R2 解冻后由同一入口服务）
        List<DanmakuEntry> due = ClientDanmakuStore.pollDue(pos,
            state.player != null ? state.player.getTimeMs() : 0);
        List<Active> actives = ACTIVE.computeIfAbsent(pos, k -> new ArrayList<>());

        // 容量闸统一丢弃（seek 涌出/刷屏场景单一截断点）
        int maxOnScreen = config.danmakuMaxOnScreen.get();
        for (DanmakuEntry entry : due) {
            if (actives.size() >= maxOnScreen) {
                KazumiLog.danmaku.debug("Danmaku dropped at {}: on-screen cap {} reached", pos, maxOnScreen);
                break;
            }
            actives.add(new Active(entry, Component.literal(entry.text()), now, nextLane()));
        }
        if (!due.isEmpty()) {
            KazumiLog.danmaku.debug("World layer consumed {} due at {} (active {})",
                due.size(), pos, actives.size());
        }
        if (actives.isEmpty()) return;

        float areaRatio = config.danmakuAreaRatio.get().floatValue();
        float fontScale = config.danmakuFontScale.get().floatValue();
        float alpha = config.danmakuOpacity.get().floatValue();
        int argb = (int) (alpha * 255.0f) << 24 | 0xFFFFFF;

        float laneWorldH = 2 * halfH * areaRatio / LANE_COUNT;
        float scale = laneWorldH / (GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR) * fontScale;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        // 屏幕面局部坐标系相对观察者为 XY 双反（视频 quad 以 U/V 双翻转补偿，见 VideoScreenRenderer UV 注释），
        // 文字层叠加同轴 Z180 真旋转对消——修正水平反向与上下倒置；det=+1 不触发背面剔除
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));

        Font font = Minecraft.getInstance().font;
        // Z180 旋转后绘制系原点在屏幕中心：可见窗口为 u ∈ [−halfWPx, +halfWPx]
        float halfWPx = halfW / scale;
        float halfHPx = halfH / scale;
        float laneHPx = GLYPH_HEIGHT_PX * LINE_HEIGHT_FACTOR;
        float textBaselineOffsetPx = 7.0f;

        Iterator<Active> it = actives.iterator();
        while (it.hasNext()) {
            Active active = it.next();
            float progress = (now - active.startMono()) / (float) travelMs;
            if (progress >= 1.0f) {
                it.remove();
                continue;
            }
            float textWPx = font.width(active.text());
            // 右进左出：p=0 时文字头贴右缘（整体藏于右缘外），p=1 时文字尾贴左缘滑出
            float headXPx = halfWPx - progress * (2 * halfWPx + textWPx);
            float yTopPx = -halfHPx + active.lane() * laneHPx + 1.0f;
            collector.submitText(poseStack, headXPx, yTopPx + textBaselineOffsetPx,
                active.text().getVisualOrderText(),
                false, Font.DisplayMode.POLYGON_OFFSET,
                LightCoordsUtil.FULL_BRIGHT, argb, 0, 0);
        }
        poseStack.popPose();
    }

    /** 断线/卸载世界时清空活动列表（BE 移除后 draw 不再被调，滞留条目靠此钩子回收） */
    public static void reset() {
        ACTIVE.clear();
    }

    /** 车道轮转游标：连续发言严格分散到不同车道（车道归属是装饰性差异，轮转最稳） */
    private static int laneCursor;

    private static int nextLane() {
        return Math.floorMod(laneCursor++, LANE_COUNT);
    }

    /** 即时项行程时长：基准 5s 除以速度倍率 */
    private static long instantTravelMs() {
        double speed = ClientConfig.CONFIG.danmakuSpeedMultiplier.get();
        return (long) (ClientDanmakuStore.INSTANT_DISPLAY_MS / speed);
    }
}
