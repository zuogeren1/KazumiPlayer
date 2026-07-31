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
     */
    private DynamicTexture getOrCreateDynamicTexture(int width, int height) {
        if (dynamicTexture == null
                || nativeImage == null
                || nativeImage.getWidth() != width
                || nativeImage.getHeight() != height) {
            if (dynamicTexture != null) {
                dynamicTexture.close();
                dynamicTexture = null;
            }
            if (nativeImage != null) {
                nativeImage.close();
                nativeImage = null;
            }
            nativeImage = new NativeImage(width, height, false);
            dynamicTexture = new DynamicTexture(
                () -> "KazumiPlayer Video Frame",
                nativeImage
            );
            if (registered) {
                // 尺寸变化后重新注册（旧 GL 纹理已释放）
                Minecraft.getInstance().getTextureManager().register(textureId, dynamicTexture);
            }
            hasValidFrame = false; // 尺寸变化，旧帧内容不再匹配
        }
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

    public Identifier getTextureId() {
        return textureId;
    }

    @Override
    public void close() {
        if (dynamicTexture != null) {
            dynamicTexture.close();
            dynamicTexture = null;
        }
        if (nativeImage != null) {
            nativeImage.close();
            nativeImage = null;
        }
        registered = false;
        hasValidFrame = false;
    }
}
