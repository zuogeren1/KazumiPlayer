package me.zuogeren.kazumiplayer.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * WaterMedia GL 纹理 → Minecraft DynamicTexture 桥接器
 *
 * 每帧从 WaterMedia 的原始 GL 纹理读回像素数据到 NativeImage，
 * 再上传到 DynamicTexture（已注册到 TextureManager），
 * 使 RenderType.entityCutout(textureId) 能正常寻址该纹理。
 */
public class VideoScreenTexture implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "video_screen_frame/";

    // 默认分辨率，后续可根据实际视频尺寸调整
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;

    private final Identifier textureId;
    private DynamicTexture dynamicTexture;
    private NativeImage nativeImage;
    private boolean registered;

    public VideoScreenTexture(String uniqueKey) {
        this.textureId = Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, PREFIX + uniqueKey);
    }

    /** 确保 DynamicTexture 已注册到 TextureManager，幂等 */
    public void ensureRegistered() {
        if (registered) return;
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        tm.register(textureId, getOrCreateDynamicTexture());
        registered = true;
        LOGGER.info("VideoScreenTexture registered: {}", textureId);
    }

    private DynamicTexture getOrCreateDynamicTexture() {
        if (dynamicTexture == null) {
            nativeImage = new NativeImage(DEFAULT_WIDTH, DEFAULT_HEIGHT, false);
            dynamicTexture = new DynamicTexture(
                () -> "KazumiPlayer Video Frame",
                nativeImage
            );
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

        DynamicTexture dt = getOrCreateDynamicTexture();
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
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to update video frame: {}", e.getMessage());
            return false;
        }
    }

    /** 用纯色填充纹理（IDLE/LOADING/ERROR 等非播放状态用） */
    public void fillPlaceholder(int colorABGR) {
        DynamicTexture dt = getOrCreateDynamicTexture();
        NativeImage img = nativeImage;
        if (img == null) return;
        img.fillRect(0, 0, img.getWidth(), img.getHeight(), colorABGR);
        dt.upload();
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
    }
}
