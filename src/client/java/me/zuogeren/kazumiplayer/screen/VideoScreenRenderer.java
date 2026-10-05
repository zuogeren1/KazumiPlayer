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

    /** 进度条高度（世界单位/格）：条自屏幕面下缘之下起，播放中恒显示 */
    private static final float PROGRESS_BAR_HEIGHT = 0.12f;
    /** 进度条上缘到屏幕面下缘的间距（世界单位/格） */
    private static final float PROGRESS_BAR_GAP = 0.05f;

    /**
     * 屏幕面底部 UI 占用（世界单位/格）= 进度条高 + 它与屏幕面下缘的间距。
     * 弹幕显示带下界按本值内缩，故弹幕（含文字框与字形外扩）恒不进入进度条所占区域。
     */
    public static float bottomUiReserveWorld() {
        return PROGRESS_BAR_HEIGHT + PROGRESS_BAR_GAP;
    }

    /** 进度条上缘到屏幕面下缘的间距（世界单位/格）：显示带越过面下缘时据此定位进度条上缘 */
    public static float bottomUiGapWorld() {
        return PROGRESS_BAR_GAP;
    }

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
        state.episodeUrl = be.getEpisodeUrl();
        state.resolveStates = be.getResolveStates();
        state.watchingPlayers = be.getWatchingPlayers();
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

        // 等待提示仅对本端参与的屏幕显示：本端不是观看者（未加入/已离开该屏）时不显示任何提示
        boolean localWatching = me.zuogeren.kazumiplayer.client.ResolveHint
            .isLocalWatchingPlayers(state.watchingPlayers);

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
            // 观察者视角等待提示：屏幕已绑定播放但本端无播放器——居中显示组内解析进度
            if (!state.episodeUrl.isEmpty() && localWatching) {
                drawWaitingHint(collector, poseStack, halfW, halfH, state.resolveStates);
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

        // 起播等待文字提示：屏幕已绑定播放、本端为观看者且尚未取得任何真实帧（解析/加载期）——
        // 居中显示组内解析进度（ResolveStates NBT 聚合），出画后自动消失
        if (!state.episodeUrl.isEmpty() && localWatching && !tex.hasValidFrame()) {
            drawWaitingHint(collector, poseStack, halfW, halfH, state.resolveStates);
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

        // 重缓冲冻结指示：播放中但时间戳停滞——视频面右上角琥珀色呼吸方块
        var bufferSp = me.zuogeren.kazumiplayer.client.ScreenPlayerManager.get(state.blockPos);
        if (!bufferSp.bypassSync && bufferSp.isBufferingFrozen()) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 170.0);
            int c = (int) (0xD0 * pulse) << 24 | 0xFFB000;
            float s = Math.max(0.06f, halfH * 0.14f);
            float mgap = Math.max(0.02f, halfH * 0.04f);
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE_TEX),
                (pose, buffer) -> fillBar(buffer, pose,
                    fXMax - s - mgap, fXMax - mgap, fYMax - s - mgap, fYMax - mgap, 0.48f, c));
        }

        // 起播等待指示：解析/加载尚未出画——视频面左上角蓝色慢呼吸方块（与缓冲角标对侧）
        if (!bufferSp.bypassSync && bufferSp.player != null && !bufferSp.everPlayed) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 320.0);
            int c = (int) (0xC8 * pulse) << 24 | 0x66Aaff;
            float s = Math.max(0.06f, halfH * 0.14f);
            float mgap = Math.max(0.02f, halfH * 0.04f);
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE_TEX),
                (pose, buffer) -> fillBar(buffer, pose,
                    fXMin + mgap, fXMin + mgap + s, fYMax - s - mgap, fYMax - mgap, 0.48f, c));
        }

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

        // 弹幕文字层（屏幕面局部坐标内右进左出）：显示带下界按本帧进度条占用内缩，不压住进度条
        me.zuogeren.kazumiplayer.client.danmaku.DanmakuWorldLayer.draw(collector, poseStack, state, halfW, halfH,
            bottomUiReserveWorld(), bottomUiGapWorld());

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

        float barH = PROGRESS_BAR_HEIGHT;
        float barY = -halfH - barH - PROGRESS_BAR_GAP;
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

    /**
     * 等待提示文字：屏幕面居中单行——组内解析进度聚合（ResolveHint），本端参与该屏且无人上报时
     * 兜底「正在解析视频源…」。行高按屏幕面高度固定占比，超宽文案按宽度收缩不溢出屏幕面。
     * 坐标系处理与弹幕层一致：scale 后 Z180 真旋转对消视频面的 XY 双反坐标系。
     */
    private static void drawWaitingHint(SubmitNodeCollector collector, PoseStack poseStack,
                                        float halfW, float halfH, String resolveStates) {
        var text = me.zuogeren.kazumiplayer.client.ResolveHint.line(
            me.zuogeren.kazumiplayer.client.ResolveHint.ofStates(resolveStates));
        var font = Minecraft.getInstance().font;
        float scale = Math.max(0.12f, halfH * 2 * 0.14f) / 9.0f;
        float maxWPx = (halfW * 2 * 0.92f) / scale;
        int tw = font.width(text);
        if (tw > maxWPx) scale *= maxWPx / tw;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));
        collector.submitText(poseStack, -tw / 2.0f, 3.5f, text.getVisualOrderText(),
            false, net.minecraft.client.gui.Font.DisplayMode.POLYGON_OFFSET,
            LightCoordsUtil.FULL_BRIGHT, 0xFFFFFFFF, 0, 0);
        poseStack.popPose();
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
     * 弹幕深度分层的层平面在屏幕面之外另占 {@code danmakuDepthSpacing × (层数−1)} 格的进深，
     * 故一并计入（层数=1 时展开量为 0），否则最深的几层会被视锥剔除；
     * 算式见 {@link me.zuogeren.kazumiplayer.client.danmaku.DanmakuDepthLayers#renderBounds}。
     */
    @Override
    public AABB getRenderBoundingBox(VideoScreenBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        var clientConfig = me.zuogeren.kazumiplayer.ClientConfig.CONFIG;
        var bounds = me.zuogeren.kazumiplayer.client.danmaku.DanmakuDepthLayers.renderBounds(
                pos.getX(), pos.getY(), pos.getZ(),
                blockEntity.getScreenWidth() / 2.0f, blockEntity.getScreenHeight() / 2.0f,
                blockEntity.getOffsetX(), blockEntity.getOffsetY(), blockEntity.getOffsetZ(),
                blockEntity.getFacing().getStepX(), blockEntity.getFacing().getStepZ(),
                clientConfig.danmakuDepthLayers.get(), clientConfig.danmakuDepthSpacing.get());
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}
