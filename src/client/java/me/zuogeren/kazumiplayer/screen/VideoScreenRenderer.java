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
    // 未播放时的屏幕面预览框颜色（深蓝灰实心）
    private static final int COLOR_IDLE_FRAME = 0xFF26264A;
    // 播放中叠加的屏幕面边框线颜色（亮青，空心描边不遮挡画面）
    private static final int COLOR_PLAYING_FRAME = 0xFF66D9E8;
    /** 播放中边框描边的线条厚度（格） */
    private static final float PLAYING_FRAME_EDGE = 0.08f;

    /** 播放中是否叠加显示屏幕面边框（屏幕设置界面内开关控制）；未播放时预览框由客户端配置 showIdleScreenFrame 控制 */
    private static volatile boolean showFrameWhilePlaying;

    public static boolean isShowFrameWhilePlaying() {
        return showFrameWhilePlaying;
    }

    /** 切换播放中边框叠加，返回切换后的状态 */
    public static boolean toggleShowFrameWhilePlaying() {
        showFrameWhilePlaying = !showFrameWhilePlaying;
        return showFrameWhilePlaying;
    }

    private static final Identifier WHITE_TEX = Identifier.fromNamespaceAndPath("kazumiplayer", "progress_bar_white");
    private static boolean whiteTexRegistered;

    private final Map<BlockPos, VideoScreenTexture> screenTextures = new java.util.HashMap<>();
    private final ItemModelResolver itemModelResolver;

    private static VideoScreenRenderer instance;

    /** GUI 视频预览用：取指定屏幕的动态纹理（未创建过时返回 null） */
    public static VideoScreenTexture getScreenTexture(BlockPos pos) {
        VideoScreenRenderer r = instance;
        return r == null ? null : r.screenTextures.get(pos);
    }

    public VideoScreenRenderer(BlockEntityRendererProvider.Context context) {
        instance = this;
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
        state.offsetX = be.getOffsetX();
        state.offsetY = be.getOffsetY();
        state.offsetZ = be.getOffsetZ();
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

        poseStack.pushPose();

        // 屏幕显示在方块上方（+0.5格避开方块遮挡进度条），叠加用户设置的 XYZ 偏移
        poseStack.translate(0.5 + state.offsetX, 1.5 + state.offsetY + halfH, 0.5 + state.offsetZ);

        // 根据朝向绕 Y 轴旋转
        rotateToFacing(poseStack, state.facing);

        // IDLE 状态（无播放器，加入同步但未开始播放）不渲染视频面——避免读到其他屏幕写入的共享纹理；
        // 按客户端配置绘制实心方框预览屏幕面的位置与设置大小，使未播放时也能在设置界面所见即所得地调整屏幕。
        // 框始终为设置的完整尺寸（不受 videoFit 等比缩放影响）
        if (state.player == null) {
            if (me.zuogeren.kazumiplayer.ClientConfig.CONFIG.showIdleScreenFrame.get()) {
                final float fw = halfW;
                final float fh = halfH;
                collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE_TEX),
                    (pose, buffer) -> fillBar(buffer, pose, -fw, fw, -fh, fh, 0.49f, COLOR_IDLE_FRAME));
            }
            poseStack.popPose();
            return;
        }

        // 决定显示视频帧还是占位色
        VideoScreenTexture tex = state.videoTexture;
        boolean switched = tex.checkPlayerChanged(state.player); // 换片：作废上一部影片残帧
        boolean hasFrame = false;
        if (state.player.isPlaying()) {
            hasFrame = tex.updateFrame(state.player);
        }

        // 已有有效帧时保留旧帧（同片 seek/缓冲导致的短暂无帧不闪占位色）；换片后立即显示加载占位
        if (!hasFrame && (!tex.hasValidFrame() || switched)) {
            tex.fillPlaceholder(COLOR_LOADING);
        }

        RenderType renderType = RenderTypes.entityCutout(tex.getTextureId());

        float z = 0.49f;
        float xMin = -halfW;
        float xMax =  halfW;
        float yMin = -halfH;
        float yMax =  halfH;

        // 屏幕为非常规比例时的播放行为（客户端配置）：
        // STRETCH=拉伸填满屏幕面（默认，历史行为）；CONTAIN=按视频宽高比在屏幕面内等比缩放居中留边
        if (me.zuogeren.kazumiplayer.ClientConfig.CONFIG.videoFit.get()
                == me.zuogeren.kazumiplayer.ClientConfig.VideoFit.CONTAIN
                && state.screenHeight > 0) {
            int vw = state.player.getWidth();
            int vh = state.player.getHeight();
            if (vw > 0 && vh > 0 && state.screenWidth > 0) {
                float videoAR = (float) vw / vh;
                float screenAR = state.screenWidth / (float) state.screenHeight;
                if (videoAR > screenAR) {
                    // 视频更宽：宽度撑满屏幕面，高度等比缩小后垂直居中
                    float drawHalfH = halfW / videoAR;
                    yMin = -drawHalfH;
                    yMax =  drawHalfH;
                } else if (videoAR < screenAR) {
                    // 视频更窄：高度撑满屏幕面，宽度等比缩小后水平居中
                    float drawHalfW = halfH * videoAR;
                    xMin = -drawHalfW;
                    xMax =  drawHalfW;
                }
            }
        }

        // UV: 上下翻转(V)+左右镜像(U)
        float uMin = 1.0f;
        float uMax = 0.0f;
        float vMin = 1.0f;
        float vMax = 0.0f;

        final float fXMin = xMin;
        final float fXMax = xMax;
        final float fYMin = yMin;
        final float fYMax = yMax;

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            addVideoQuad(buffer, pose, fXMin, fXMax, fYMin, fYMax, z, uMin, uMax, vMin, vMax);
        });

        // 播放中叠加显示屏幕面边框（屏幕设置内开关控制）：
        // 空心描边（四条窄条，紧贴视频面前缘 z=0.485），只框出设置范围、不遮挡画面内容
        if (showFrameWhilePlaying) {
            final float fw = halfW;
            final float fh = halfH;
            final float e = PLAYING_FRAME_EDGE;
            final int c = COLOR_PLAYING_FRAME;
            RenderType frameType = RenderTypes.entityCutout(WHITE_TEX);
            // 上边 / 下边 / 左边 / 右边
            collector.submitCustomGeometry(poseStack, frameType,
                (pose, buffer) -> fillBar(buffer, pose, -fw, fw, fh - e, fh, 0.485f, c));
            collector.submitCustomGeometry(poseStack, frameType,
                (pose, buffer) -> fillBar(buffer, pose, -fw, fw, -fh, -fh + e, 0.485f, c));
            collector.submitCustomGeometry(poseStack, frameType,
                (pose, buffer) -> fillBar(buffer, pose, -fw, -fw + e, -fh + e, fh - e, 0.485f, c));
            collector.submitCustomGeometry(poseStack, frameType,
                (pose, buffer) -> fillBar(buffer, pose, fw - e, fw, -fh + e, fh - e, 0.485f, c));
        }

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
     * 需计入用户设置的 XYZ 偏移，否则偏移后的屏幕面会被剔除。
     */
    @Override
    public AABB getRenderBoundingBox(VideoScreenBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        float halfW = blockEntity.getScreenWidth() / 2.0f;
        float halfH = blockEntity.getScreenHeight() / 2.0f;
        // 屏幕面：默认中心在方块上方 1.5+halfH，半宽 halfW，半高 halfH；水平方向按最大半径覆盖
        double r = Math.max(1.0, halfW) + 0.5;
        double cx = pos.getX() + 0.5 + blockEntity.getOffsetX();
        double cy = pos.getY() + 1.5 + blockEntity.getOffsetY() + halfH;
        double cz = pos.getZ() + 0.5 + blockEntity.getOffsetZ();
        return new AABB(
                Math.min(pos.getX() + 0.5 - r, cx - r), Math.min(pos.getY(), cy - halfH - 1.0),
                Math.min(pos.getZ() + 0.5 - r, cz - r),
                Math.max(pos.getX() + 0.5 + r, cx + r), Math.max(pos.getY() + 1.5 + halfH * 2 + 1.0, cy + halfH + 1.0),
                Math.max(pos.getZ() + 0.5 + r, cz + r));
    }
}
