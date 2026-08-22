package me.zuogeren.kazumiplayer.screen;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import com.mojang.blaze3d.platform.NativeImage;
import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * WaterMedia GL 纹理 → Minecraft DynamicTexture 桥接器
 *
 * 每帧从 WaterMedia 的原始 GL 纹理读回像素数据到 NativeImage，
 * 再上传到 DynamicTexture（已注册到 TextureManager），
 * 使 RenderType.entityCutout(textureId) 能正常寻址该纹理。
 */
public class VideoScreenTexture implements AutoCloseable {
    private static final String PREFIX = "video_screen_frame/";

    // 占位色纹理分辨率（实际视频帧就绪前使用）
    private static final int PLACEHOLDER_WIDTH = 320;
    private static final int PLACEHOLDER_HEIGHT = 180;

    private final Identifier textureId;
    private DynamicTexture dynamicTexture;
    private NativeImage nativeImage;
    private boolean registered;
    private boolean hasValidFrame;
    private WaterMediaPlayer boundPlayer; // 上次写入帧的播放器实例，用于识别换片

    public VideoScreenTexture(String uniqueKey) {
        this.textureId = Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, PREFIX + uniqueKey);
    }

    /** 确保 DynamicTexture 已注册到 TextureManager，幂等 */
    public void ensureRegistered() {
        if (registered) return;
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        tm.register(textureId, getOrCreateDynamicTexture(PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT));
        registered = true;
        KazumiLog.render.info("VideoScreenTexture registered: {}", textureId);
    }

    /**
     * 按目标尺寸创建/重建纹理。
     * 视频分辨率变化时自动调整，避免固定尺寸读回导致画面错乱（上半部分/重复画面/摩尔纹）。
     *
     * 重建必须采用「先建新、后覆盖注册」顺序：旧纹理由 TextureManager.register 替换时
     * 统一 safeClose，保证 textureId 在 manager 中任何时刻都指向完好的纹理——
     * GUI 预览在 extract 阶段抓取 GpuTextureView 引用、帧末才实际绘制，
     * 若 textureId 一度指向已关闭视图会导致 "Texture view has been closed" 崩溃。
     * （注意 DynamicTexture.close() 会连带关闭传入的 NativeImage，禁止再手动关闭）
     */
    private DynamicTexture getOrCreateDynamicTexture(int width, int height) {
        if (dynamicTexture != null && nativeImage != null
                && nativeImage.getWidth() == width
                && nativeImage.getHeight() == height) {
            return dynamicTexture;
        }
        nativeImage = new NativeImage(width, height, false);
        dynamicTexture = new DynamicTexture(
            () -> "KazumiPlayer Video Frame",
            nativeImage
        );
        if (registered) {
            // 覆盖注册：manager 自动 safeClose 被替换的旧纹理（含其 pixels）
            Minecraft.getInstance().getTextureManager().register(textureId, dynamicTexture);
        }
        hasValidFrame = false; // 尺寸变化，旧帧内容不再匹配
        return dynamicTexture;
    }

    /**
     * 从 WaterMedia 的 GL 纹理读回最新帧，上传到 DynamicTexture
     * @return true 表示成功拷贝了一帧
     */
    public boolean updateFrame(WaterMediaPlayer wmPlayer) {
        if (wmPlayer == null) return false;

        long texId = wmPlayer.getTextureId();
        if (texId == 0) return false;
        int width = wmPlayer.getWidth();
        int height = wmPlayer.getHeight();
        if (width <= 0 || height <= 0) return false; // 解码器尚未就绪

        DynamicTexture dt = getOrCreateDynamicTexture(width, height);
        NativeImage img = nativeImage;
        if (img == null) return false;

        try {
            // 保存当前纹理绑定，随后恢复
            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, (int) texId);

            long pixelPtr = img.getPointer();
            int sizeBytes = img.getWidth() * img.getHeight() * 4;
            ByteBuffer buffer = MemoryUtil.memByteBuffer(pixelPtr, sizeBytes);
            // 使用 GL_RGBA 格式读回，匹配 NativeImage 内部 RGBA 字节序
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);

            dt.upload();
            hasValidFrame = true;
            return true;
        } catch (Exception e) {
            KazumiLog.render.error("Failed to update video frame: {}", e.getMessage());
            return false;
        }
    }

    /** 用纯色填充纹理（IDLE/LOADING/ERROR 等非播放状态用） */
    public void fillPlaceholder(int colorABGR) {
        DynamicTexture dt = getOrCreateDynamicTexture(PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT);
        NativeImage img = nativeImage;
        if (img == null) return;
        img.fillRect(0, 0, img.getWidth(), img.getHeight(), colorABGR);
        dt.upload();
    }

    /** 是否已有至少一帧有效视频画面（无帧时用于决定是保留旧帧还是填充占位色） */
    public boolean hasValidFrame() {
        return hasValidFrame;
    }

    /**
     * 检测播放器实例是否更换（切集/自动连播会重建播放器）。
     * 更换时立即作废上一部影片的残帧——否则新视频解码就绪前
     * 屏幕上一直定格着上一部的最后一帧；同一播放器的 seek/缓冲不受影响。
     *
     * @return true 表示刚发生了换片（调用方通常需要填充占位色）
     */
    public boolean checkPlayerChanged(WaterMediaPlayer current) {
        if (this.boundPlayer == current) return false;
        this.boundPlayer = current;
        this.hasValidFrame = false;
        return true;
    }

    public Identifier getTextureId() {
        return textureId;
    }

    @Override
    public void close() {
        // 从 TextureManager 正规注销（移除条目并 safeClose 纹理），
        // 避免留下指向已关闭纹理的死 id——后续按 id 寻址的绘制会命中已关闭视图
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            registered = false;
        }
        dynamicTexture = null;
        nativeImage = null;
        hasValidFrame = false;
    }
}
