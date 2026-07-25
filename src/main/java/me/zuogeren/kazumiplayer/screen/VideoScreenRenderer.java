package me.zuogeren.kazumiplayer.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

public class VideoScreenRenderer implements BlockEntityRenderer<VideoScreenBlockEntity, VideoScreenRenderState> {

    // 各状态对应的占位颜色 (ABGR packed int)
    // IDLE/STOPPED → 深灰, LOADING → 深蓝, ERROR → 深红
    private static final int COLOR_IDLE    = 0xFF333333;
    private static final int COLOR_LOADING = 0xFF333388;
    private static final int COLOR_ERROR   = 0xFF883333;

    private final VideoScreenTexture videoTexture;

    public VideoScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.videoTexture = new VideoScreenTexture();
        this.videoTexture.ensureRegistered();
    }

    @Override
    public VideoScreenRenderState createRenderState() {
        return new VideoScreenRenderState();
    }

    @Override
    public void extractRenderState(VideoScreenBlockEntity be, VideoScreenRenderState state,
            float partialTicks, Vec3 cameraPos,
            ModelFeatureRenderer.@org.jspecify.annotations.Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderState.extractBase(be, state, breakProgress);
        state.screenWidth = be.getScreenWidth();
        state.screenHeight = be.getScreenHeight();
        state.facing = be.getFacing();
        state.videoState = be.getVideoState();
        state.player = be.player;
    }

    @Override
    public void submit(VideoScreenRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {

        // 屏幕半宽高 (单位: 方块, 1.0 = 1 block)
        float halfW = state.screenWidth / 2.0f;
        float halfH = state.screenHeight / 2.0f;

        // 决定显示视频帧还是占位色
        boolean hasFrame = false;
        if (state.player != null && state.player.isPlaying()) {
            hasFrame = videoTexture.updateFrame(state.player);
        }

        if (!hasFrame) {
            int color;
            if (state.player != null) {
                // 有播放器但未就绪 → LOADING
                color = COLOR_LOADING;
            } else {
                color = COLOR_IDLE;
            }
            videoTexture.fillPlaceholder(color);
        }

        RenderType renderType = RenderTypes.entityCutout(videoTexture.getTextureId());

        poseStack.pushPose();

        // 屏幕显示在方块上方，不与方块重叠
        poseStack.translate(0.5, 1.0 + halfH, 0.5);

        // 根据朝向绕 Y 轴旋转，使四边形正面朝向玩家
        rotateToFacing(poseStack, state.facing);

        // 四边形在屏幕前方 (Z+ = 屏幕正面)
        // halfW/halfH 决定屏幕尺寸，Z 略微前移避免 z-fighting
        float z = 0.51f;
        float xMin = -halfW;
        float xMax =  halfW;
        float yMin = -halfH;
        float yMax =  halfH;

        // UV: 上下翻转(V)+左右镜像(U)
        float uMin = 1.0f;
        float uMax = 0.0f;
        float vMin = 1.0f;
        float vMax = 0.0f;

        // submitCustomGeometry 签名: (PoseStack, RenderType, BiConsumer<PoseStack.Pose, VertexConsumer>)
        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            // 四边形: 4 顶点逆时针 (从正面看: 左下 → 右下 → 右上 → 左上)
            addVertex(buffer, pose, xMin, yMin, z, uMin, vMin); // BL
            addVertex(buffer, pose, xMax, yMin, z, uMax, vMin); // BR
            addVertex(buffer, pose, xMax, yMax, z, uMax, vMax); // TR
            addVertex(buffer, pose, xMin, yMax, z, uMin, vMax); // TL
        });

        poseStack.popPose();
    }

    /** 绕 Y 轴旋转使四边形正面朝向指定方向 */
    private static void rotateToFacing(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
            case EAST  -> poseStack.mulPose(Axis.YP.rotationDegrees(90.0f));
            case WEST  -> poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
            // SOUTH: 默认 +Z 方向，无需旋转
            default -> {}
        }
    }

    /** 添加一个带完整属性的顶点 (全亮、无覆盖、法线向前) */
    private static void addVertex(VertexConsumer vc, PoseStack.Pose pose,
                                   float x, float y, float z,
                                   float u, float v) {
        vc.addVertex(pose, x, y, z)
          .setColor(-1)                              // 0xFFFFFFFF = 白色
          .setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, 0.0f, 0.0f, 1.0f);        // 法线朝前 (+Z)
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }
}
