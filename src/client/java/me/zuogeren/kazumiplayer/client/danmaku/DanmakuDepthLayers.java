package me.zuogeren.kazumiplayer.client.danmaku;

/**
 * 屏幕面弹幕的深度分层（纯算术：世界层渲染与渲染包围盒共用，无 Minecraft 依赖，离线 harness 可直接驱动）。
 *
 * <p><b>分层几何</b>：层平面与屏幕面平行，第 i 层沿屏幕面法线朝观察者方向平移
 * {@code i × danmakuDepthSpacing}（世界单位）。观察者所在的本地 Z 侧见 {@link #OBSERVER_Z_SIGN}：
 * 世界层内容（视频 quad 与文字层）只在屏幕面局部坐标系的 <b>−Z 侧</b>可读，故「朝观察者」的
 * 平移量为负 z。推导与验证方式见 {@code reference/plans/f11-danmaku-depth-layers.md} §2。
 *
 * <p><b>层分配</b>：新条目按轮转落到各层——第 n 条落到第 {@code n mod 层数} 层，故任意连续 n 条
 * 的层间分配数差 ≤ 1（{@link #layerFor}）；每层内部各自执行原有的连续纵向打包与碰撞判据，
 * 层间不共享占用（层间距远大于弹幕文本框厚度，层间不做相交判定）。
 *
 * <p><b>同屏上限语义</b>：有效上限 = {@code danmakuScreenCap()} × 层数（{@link #effectiveScreenCap}）。
 * 上限闸先于落点准入，若不同步放大，层数只会让每层变稀而不增加同屏条数；压叠模式
 * （danmakuAllowOverlap 或密度档位=OVERLAP）下上限本就不生效，不受层数影响。
 *
 * <p>层数 = 1（默认）时本类退化为「无平移 + 原上限 + 恒第 0 层」，世界层绘制路径与单层实现逐位一致。
 */
public final class DanmakuDepthLayers {

    /** 层数上限（与 {@code ClientConfig.danmakuDepthLayers} 的 1..4 同域，越界入参收口用） */
    public static final int MAX_LAYERS = 4;
    /** 层间距下限（格，与 {@code ClientConfig.danmakuDepthSpacing} 的 0.05..2.0 同域） */
    public static final double MIN_SPACING = 0.05;
    /** 层间距上限（格） */
    public static final double MAX_SPACING = 2.0;

    /**
     * 观察者所在的屏幕面本地 Z 侧符号：世界层内容只在 −Z 侧可读，故「朝观察者」= −z。
     * 依据（详见 plans/f11-danmaku-depth-layers.md §2）：
     * ① 视频 quad 的 UV（xMin↔u=1）对 −Z 侧观察者给出未镜像画面，且与 GUI 预览/全屏 blit
     * 同一张纹理的自然映射一致；② 文字层的 Z180（det=+1，字形正面法线 −Z）只对 −Z 侧给出正读且
     * 正面朝向（文字 RenderType 的剔面开启，+Z 侧会被剔除）；③ 弹幕基准面 z=0 与视频面 z=+0.49
     * 之间的可见深度预算只在 −z 方向无界（+z 方向仅 0.49 格，按间距上限 2.0 会立刻穿到视频面之后被遮挡）。
     */
    public static final float OBSERVER_Z_SIGN = -1.0f;

    private DanmakuDepthLayers() {}

    /** 层数收口：clamp 到 [1, {@link #MAX_LAYERS}] */
    public static int layers(int raw) {
        return Math.max(1, Math.min(MAX_LAYERS, raw));
    }

    /** 层间距收口（格）：clamp 到 [{@link #MIN_SPACING}, {@link #MAX_SPACING}]，非有限值取下限 */
    public static double spacing(double raw) {
        if (Double.isNaN(raw)) return MIN_SPACING;
        return Math.max(MIN_SPACING, Math.min(MAX_SPACING, raw));
    }

    /**
     * 有效同屏上限 = 基础上限 × 层数（社交条目本就不受上限约束；压叠模式下上限不生效）。
     * 密度档位=OVERLAP 时基础上限为 {@link Integer#MAX_VALUE}，故按 long 相乘后饱和回 int。
     */
    public static int effectiveScreenCap(int baseCap, int layers) {
        return (int) Math.min(Integer.MAX_VALUE, (long) baseCap * layers(layers));
    }

    /**
     * 第 layer 层的世界平移量（世界单位，仅 z 分量）：沿屏幕面法线朝观察者方向。
     * 层数=1 或 layer=0 时为 0.0（不做任何额外平移）。
     */
    public static float layerOffsetZ(int layer, double spacing) {
        return OBSERVER_Z_SIGN * (float) (layer * spacing(spacing));
    }

    /** 最深一层相对基准面的世界深度（格）：包围盒按 facing 轴分量展开这么多即覆盖全部层平面 */
    public static double layerExtent(int layers, double spacing) {
        return (layers(layers) - 1) * spacing(spacing);
    }

    /** 轮转分配：第 cursor 个新条目落到第 {@code cursor mod 层数} 层（层间分配数差 ≤ 1） */
    public static int layerFor(long cursor, int layers) {
        return (int) Math.floorMod(cursor, (long) layers(layers));
    }

    /**
     * 渲染包围盒六面（世界坐标，纯算术）：屏幕面区域并上「前方最深一层」的体积。
     *
     * <p>屏幕面中心在方块上方 {@code 1.5 + halfH}，其平面沿 {@code facing} 轴展开；层平面与屏幕面平行，
     * 沿 facing 轴朝观察者偏移，故把水平半径按 facing 轴分量放大 {@link #layerExtent} 格即可覆盖全部层。
     * 层数=1 时展开量为 0，返回的六面与单层实现的算式逐位一致。
     *
     * @param facingStepX facing 方向的 x 分量（-1/0/1）
     * @param facingStepZ facing 方向的 z 分量（-1/0/1）
     */
    public static Bounds renderBounds(double posX, double posY, double posZ, double halfW, double halfH,
                                      double offsetX, double offsetY, double offsetZ,
                                      int facingStepX, int facingStepZ, int layers, double spacing) {
        double extent = layerExtent(layers, spacing);
        // 屏幕面：半宽 halfW、半高 halfH；水平方向按最大半径覆盖（旋转后各轴分量 ≤ halfW）
        double radius = Math.max(1.0, halfW) + 0.5;
        double rx = radius + Math.abs(facingStepX) * extent;
        double rz = radius + Math.abs(facingStepZ) * extent;
        double cx = posX + 0.5 + offsetX;
        double cy = posY + 1.5 + offsetY + halfH;
        double cz = posZ + 0.5 + offsetZ;
        return new Bounds(
            Math.min(posX + 0.5 - rx, cx - rx),
            Math.min(posY, cy - halfH - 1.0),
            Math.min(posZ + 0.5 - rz, cz - rz),
            Math.max(posX + 0.5 + rx, cx + rx),
            Math.max(posY + 1.5 + halfH * 2 + 1.0, cy + halfH + 1.0),
            Math.max(posZ + 0.5 + rz, cz + rz));
    }

    /** 包围盒六面（世界坐标） */
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
}
