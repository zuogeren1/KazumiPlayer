package me.zuogeren.kazumiplayer.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public class VideoScreenRenderer implements BlockEntityRenderer<VideoScreenBlockEntity, VideoScreenRenderState> {

    // 各状态对应的占位颜色 (ABGR packed int)
    // IDLE/STOPPED → 深灰, LOADING → 深蓝, ERROR → 深红
    private static final int COLOR_IDLE    = 0xFF333333;
    private static final int COLOR_LOADING = 0xFF333388;
    private static final int COLOR_ERROR   = 0xFF883333;

    private static final Identifier BLOCK_PLACEHOLDER = Identifier.fromNamespaceAndPath("kazumiplayer", "block_placeholder");
    private static boolean placeholderRegistered;

    private final VideoScreenTexture videoTexture;
    private final ItemModelResolver itemModelResolver;

    public VideoScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.videoTexture = new VideoScreenTexture();
        this.videoTexture.ensureRegistered();
        ensurePlaceholderRegistered();
        this.itemModelResolver = context.itemModelResolver();
    }

    private static void ensurePlaceholderRegistered() {
        if (placeholderRegistered) return;
        placeholderRegistered = true;
        try {
            var mc = Minecraft.getInstance();
            var res = mc.getResourceManager()
                .getResource(Identifier.fromNamespaceAndPath("kazumiplayer", "textures/block/video_screen_placeholder.png"));
            if (res.isPresent()) {
                var img = NativeImage.read(res.get().open());
                mc.getTextureManager().register(BLOCK_PLACEHOLDER,
                    new DynamicTexture(() -> "kazumiplayer_block_placeholder", img));
            }
        } catch (Exception ignored) {}
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
        state.skinBlock = be.getSkinBlock();
        // 解析皮肤物品模型
        state.skinItemState = null;
        String skin = be.getSkinBlock();
        if (!skin.isEmpty()) {
            var id = net.minecraft.resources.Identifier.tryParse(skin);
            if (id != null) {
                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
                if (block != null) {
                    ItemStack stack = new ItemStack(block.asItem());
                    var itemState = new ItemStackRenderState();
                    itemModelResolver.updateForTopItem(itemState, stack,
                        ItemDisplayContext.HEAD, be.getLevel(), null, 0);
                    state.skinItemState = itemState;
                }
            }
        }
    }

    @Override
    public void submit(VideoScreenRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {

        // 方块皮肤渲染
        drawSkin(collector, poseStack, state);

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

        // 屏幕显示在方块上方
        poseStack.translate(0.5, 1.0 + halfH, 0.5);

        // 根据朝向绕 Y 轴旋转
        rotateToFacing(poseStack, state.facing);

        float z = 0.49f;
        float xMin = -halfW;
        float xMax =  halfW;
        float yMin = -halfH;
        float yMax =  halfH;

        // UV: 上下翻转(V)+左右镜像(U)
        float uMin = 1.0f;
        float uMax = 0.0f;
        float vMin = 1.0f;
        float vMax = 0.0f;

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            addVideoQuad(buffer, pose, xMin, xMax, yMin, yMax, z, uMin, uMax, vMin, vMax);
        });

        // 进度条（画面下方）
        drawProgressBar(collector, poseStack, state, halfW, halfH);

        poseStack.popPose();
    }

    private void drawSkin(SubmitNodeCollector collector, PoseStack poseStack,
                           VideoScreenRenderState state) {
        if (state.skinItemState != null) {
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.scale(1.0f, 1.0f, 1.0f);
            state.skinItemState.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        } else {
            // 占位立方体（使用独立贴图，不被视频帧覆盖）
            var rt = RenderTypes.entityCutout(BLOCK_PLACEHOLDER);
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            float s = 0.5f;
            collector.submitCustomGeometry(poseStack, rt, (pose, buffer) -> {
                quad(buffer, pose, -s, s, -s, s, s, -s, s, s, s, -s, s, s, 0, 1, 0);    // top
                quad(buffer, pose, -s, -s, s, s, -s, s, s, -s, -s, -s, -s, -s, 0, -1, 0); // bottom
                quad(buffer, pose, s, -s, -s, -s, -s, -s, -s, s, -s, s, s, -s, 0, 0, -1);  // north
                quad(buffer, pose, -s, -s, s, s, -s, s, s, s, s, -s, s, s, 0, 0, 1);      // south
                quad(buffer, pose, -s, -s, -s, -s, -s, s, -s, s, s, -s, s, -s, -1, 0, 0); // west
                quad(buffer, pose, s, -s, s, s, -s, -s, s, s, -s, s, s, s, 1, 0, 0);     // east
            });
            poseStack.popPose();
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                              float x0, float y0, float z0, float x1, float y1, float z1,
                              float x2, float y2, float z2, float x3, float y3, float z3,
                              float nx, float ny, float nz) {
        vc.addVertex(pose, x0, y0, z0).setColor(-1).setUv(0, 0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x1, y1, z1).setColor(-1).setUv(1, 0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x2, y2, z2).setColor(-1).setUv(1, 1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x3, y3, z3).setColor(-1).setUv(0, 1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, nx, ny, nz);
    }

    private void drawProgressBar(SubmitNodeCollector collector, PoseStack poseStack,
                                  VideoScreenRenderState state, float halfW, float halfH) {
        if (state.player == null) return;
        long duration = state.player.getDurationMs();
        long time = state.player.getTimeMs();
        if (duration <= 0) return;

        float barH = 0.12f;
        float barY = -halfH - barH - 0.05f;
        float ratio = Math.min(1.0f, (float) time / duration);
        float totalW = halfW * 2;
        float playedW = totalW * ratio;
        float zBg = 0.49f;
        float zFg = 0.47f;

        RenderType barType = RenderTypes.entityCutout(BLOCK_PLACEHOLDER);

        // 背景条（深灰）
        collector.submitCustomGeometry(poseStack, barType, (pose, buffer) -> {
            fillBar(buffer, pose, -halfW, halfW, barY, barY + barH, zBg, 0xFF555555);
        });

        // 已播放条（绿色），从左侧开始填充
        if (playedW > 0) {
            float pxEnd = -halfW + playedW;
            collector.submitCustomGeometry(poseStack, barType, (pose, buffer) -> {
                fillBar(buffer, pose, -halfW, pxEnd, barY, barY + barH, zFg, 0xFF44FF33);
            });
        }
    }

    private static void addVideoQuad(VertexConsumer buffer, PoseStack.Pose pose,
                                      float xMin, float xMax, float yMin, float yMax, float z,
                                      float uMin, float uMax, float vMin, float vMax) {
        addVertex(buffer, pose, xMin, yMin, z, uMin, vMin);
        addVertex(buffer, pose, xMax, yMin, z, uMax, vMin);
        addVertex(buffer, pose, xMax, yMax, z, uMax, vMax);
        addVertex(buffer, pose, xMin, yMax, z, uMin, vMax);
    }

    private static void fillBar(VertexConsumer vc, PoseStack.Pose pose,
                                  float xMin, float xMax, float yMin, float yMax, float z, int color) {
        // CCW 顶点序: BL→BR→TR→TL
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

    private static void addVertex(VertexConsumer vc, PoseStack.Pose pose,
                                   float x, float y, float z, float u, float v) {
        vc.addVertex(pose, x, y, z).setColor(-1).setUv(u, v)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
          .setNormal(pose, 0, 0, 1);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }
}
