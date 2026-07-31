package me.zuogeren.kazumiplayer.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import java.util.Map;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class VideoScreenRenderer implements BlockEntityRenderer<VideoScreenBlockEntity, VideoScreenRenderState> {

    // LOADING 状态占位色 (ABGR packed int)
    private static final int COLOR_LOADING = 0xFF333388;

    private static final Identifier WHITE_TEX = Identifier.fromNamespaceAndPath("kazumiplayer", "progress_bar_white");
    private static boolean whiteTexRegistered;

    private final Map<BlockPos, VideoScreenTexture> screenTextures = new java.util.HashMap<>();
    private final ItemModelResolver itemModelResolver;

    public VideoScreenRenderer(BlockEntityRendererProvider.Context context) {
        ensureProgressBarTexture();
        this.itemModelResolver = context.itemModelResolver();
    }

    private static void ensureProgressBarTexture() {
        if (whiteTexRegistered) return;
        whiteTexRegistered = true;
        var mc = Minecraft.getInstance();
        // 1x1 纯白纹理，进度条着色用
        var white = new NativeImage(1, 1, false);
        white.setPixel(0, 0, 0xFFFFFFFF);
        mc.getTextureManager().register(WHITE_TEX,
            new DynamicTexture(() -> "progress_bar_white", white));
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
        state.player = me.zuogeren.kazumiplayer.client.ScreenPlayerManager.getPlayer(be.getBlockPos());
        state.videoTexture = screenTextures.computeIfAbsent(be.getBlockPos(),
            k -> {
                String key = k.getX() + "_" + k.getY() + "_" + k.getZ();
                var t = new VideoScreenTexture(key);
                t.ensureRegistered();
                return t;
            });
        state.skinBlock = be.getSkinBlock();
        // 方块模型：统一走 ItemModelResolver（支持资源包替换纹理）
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
        } else {
            // 默认：渲染自身 BlockItem（items/ JSON → video_screen_display model → cube_all+占位纹理）
            ItemStack stack = new ItemStack(VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ITEM.get());
            var itemState = new ItemStackRenderState();
            itemModelResolver.updateForTopItem(itemState, stack,
                ItemDisplayContext.HEAD, be.getLevel(), null, 0);
            state.skinItemState = itemState;
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

        // IDLE 状态（无播放器）不渲染视频面——避免读到其他屏幕写入的共享纹理
        if (state.player == null) return;

        // 决定显示视频帧还是占位色
        VideoScreenTexture tex = state.videoTexture;
        boolean hasFrame = false;
        if (state.player.isPlaying()) {
            hasFrame = tex.updateFrame(state.player);
        }

        // 已有有效帧时保留旧帧（seek/缓冲导致的短暂无帧不闪占位色）
        if (!hasFrame && !tex.hasValidFrame()) {
            tex.fillPlaceholder(COLOR_LOADING);
        }

        RenderType renderType = RenderTypes.entityCutout(tex.getTextureId());

        poseStack.pushPose();

        // 屏幕显示在方块上方（+0.5格避开方块遮挡进度条）
        poseStack.translate(0.5, 1.5 + halfH, 0.5);

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
        // 统一走 ItemStackRenderState.submit()：有皮肤→皮肤方块，无皮肤→默认 BlockItem
        if (state.skinItemState != null) {
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            state.skinItemState.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
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

        RenderType barType = RenderTypes.entityCutout(WHITE_TEX);

        // 背景条（深灰）
        collector.submitCustomGeometry(poseStack, barType, (pose, buffer) -> {
            fillBar(buffer, pose, -halfW, halfW, barY, barY + barH, zBg, 0xFF555555);
        });

        // 已播放条（绿色），从右端向左填充
        if (playedW > 0) {
            float pxStart = halfW - playedW;
            collector.submitCustomGeometry(poseStack, barType, (pose, buffer) -> {
                fillBar(buffer, pose, pxStart, halfW, barY, barY + barH, zFg, 0xFF44FF33);
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

    /**
     * 渲染包围盒必须包含伸出方块上方的屏幕面，否则玩家视角看不到方块本体
     * AABB 时整个 BE 被 frustum 剔除，屏幕面消失（方块本体由 chunk 渲染所以正常）。
     */
    @Override
    public AABB getRenderBoundingBox(VideoScreenBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        float halfW = blockEntity.getScreenWidth() / 2.0f;
        float halfH = blockEntity.getScreenHeight() / 2.0f;
        // 屏幕面：中心在方块上方 1.5+halfH，半宽 halfW，半高 halfH；水平方向按最大半径覆盖
        double r = Math.max(1.0, halfW) + 0.5;
        double yTop = pos.getY() + 1.5 + halfH * 2 + 1.0;
        return new AABB(
                pos.getX() + 0.5 - r, pos.getY(), pos.getZ() + 0.5 - r,
                pos.getX() + 0.5 + r, yTop, pos.getZ() + 0.5 + r);
    }
}
