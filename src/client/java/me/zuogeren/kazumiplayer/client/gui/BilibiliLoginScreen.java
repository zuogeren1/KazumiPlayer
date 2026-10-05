package me.zuogeren.kazumiplayer.client.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.mojang.blaze3d.platform.NativeImage;
import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.client.BilibiliLoginApi;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * B 站扫码登录界面：请求二维码 → 用 zxing 编码为纹理 → 每秒轮询登录状态。
 * 成功后把凭据写入客户端配置（本地优先于服务端下发的共享凭据）并刷新 WaterMedia 平台配置。
 */
public class BilibiliLoginScreen extends Screen {

    private static final Identifier QR_TEXTURE =
            Identifier.fromNamespaceAndPath("kazumiplayer", "bilibili_login_qr");
    private static final int QR_BOX = 176;

    private final Screen parent;

    private String qrcodeKey = "";
    private boolean qrReady;
    private int qrPixelWidth = QR_BOX;
    private int qrPixelHeight = QR_BOX;
    private DynamicTexture qrTexture;

    private String statusKey = "kazumiplayer.gui.bili_login.loading";
    private int statusColor = 0xFFAAAAAA;
    private boolean done;
    private int pollTicker;

    public BilibiliLoginScreen(Screen parent) {
        super(Component.translatable("kazumiplayer.gui.bili_login.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("kazumiplayer.gui.bili_login.refresh"),
                b -> requestQrCode())
            .bounds(this.width / 2 - 154, this.height - 36, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("kazumiplayer.gui.main.btn_close"),
                b -> this.onClose())
            .bounds(this.width / 2 + 4, this.height - 36, 150, 20).build());
        requestQrCode();
    }

    /** 申请二维码并重建纹理（异步请求，纹理上传回主线程执行） */
    private void requestQrCode() {
        if (done) return;
        qrReady = false;
        statusKey = "kazumiplayer.gui.bili_login.loading";
        statusColor = 0xFFAAAAAA;
        BilibiliLoginApi.generate().whenComplete((session, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                statusKey = "kazumiplayer.gui.bili_login.failed";
                statusColor = 0xFFFF8888;
                KazumiLog.network.warn("Bilibili qr generate failed: {}",
                    String.valueOf(error.getMessage()));
                return;
            }
            qrcodeKey = session.qrcodeKey();
            pollTicker = 0;
            buildQrTexture(session.url());
        }));
    }

    /** 用 zxing 编码二维码为位图纹理（按模块整数倍放大，NEAREST 采样保持清晰） */
    private void buildQrTexture(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8"));
            int mw = matrix.getWidth();
            int mh = matrix.getHeight();
            int scale = Math.max(1, (QR_BOX - 16) / Math.max(mw, mh));
            int px = mw * scale;
            int py = mh * scale;
            NativeImage image = new NativeImage(px, py, false);
            for (int y = 0; y < py; y++) {
                for (int x = 0; x < px; x++) {
                    image.setPixel(x, y, matrix.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            releaseQrTexture();
            qrTexture = new DynamicTexture(() -> "kazumiplayer bilibili qr", image);
            Minecraft.getInstance().getTextureManager().register(QR_TEXTURE, qrTexture);
            qrPixelWidth = px;
            qrPixelHeight = py;
            qrReady = true;
            statusKey = "kazumiplayer.gui.bili_login.waiting";
            statusColor = 0xFFCCCCCC;
        } catch (Throwable t) {
            statusKey = "kazumiplayer.gui.bili_login.failed";
            statusColor = 0xFFFF8888;
            KazumiLog.network.warn("Bilibili qr encode failed: {}", String.valueOf(t.getMessage()));
        }
    }

    private void releaseQrTexture() {
        if (qrTexture != null) {
            Minecraft.getInstance().getTextureManager().release(QR_TEXTURE);
            qrTexture.close();
            qrTexture = null;
        }
        qrReady = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (done || qrcodeKey.isEmpty() || !qrReady) return;
        if (++pollTicker < 20) return; // 每秒一次
        pollTicker = 0;
        String key = qrcodeKey;
        BilibiliLoginApi.poll(key).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            if (done || !key.equals(qrcodeKey)) return;
            if (error != null) {
                statusKey = "kazumiplayer.gui.bili_login.failed";
                statusColor = 0xFFFF8888;
                KazumiLog.network.warn("Bilibili qr poll failed: {}", String.valueOf(error.getMessage()));
                return;
            }
            handlePoll(result);
        }));
    }

    private void handlePoll(BilibiliLoginApi.PollResult result) {
        switch (result.state()) {
            case WAITING -> {
                statusKey = "kazumiplayer.gui.bili_login.waiting";
                statusColor = 0xFFCCCCCC;
            }
            case SCANNED -> {
                statusKey = "kazumiplayer.gui.bili_login.scanned";
                statusColor = 0xFFFFDD66;
            }
            case EXPIRED -> {
                statusKey = "kazumiplayer.gui.bili_login.expired";
                statusColor = 0xFFFF8888;
                qrReady = false;
                releaseQrTexture();
            }
            case CONFIRMED -> {
                if (result.cookie().isBlank()) {
                    statusKey = "kazumiplayer.gui.bili_login.failed";
                    statusColor = 0xFFFF8888;
                    return;
                }
                ClientConfig.CONFIG.bilibiliCookie.set(result.cookie());
                ClientConfig.SPEC.save();
                done = true;
                statusKey = "kazumiplayer.gui.bili_login.success";
                statusColor = 0xFF55FF55;
                KazumiLog.network.info("Bilibili login succeeded, cookie stored locally ({} chars)",
                    result.cookie().length());
            }
            default -> {
                statusKey = "kazumiplayer.gui.bili_login.failed";
                statusColor = 0xFFFF8888;
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

        int boxX = (this.width - QR_BOX) / 2;
        int boxY = 40;
        graphics.fill(boxX, boxY, boxX + QR_BOX, boxY + QR_BOX, 0xFF101010);
        if (qrReady) {
            int qx = boxX + (QR_BOX - qrPixelWidth) / 2;
            int qy = boxY + (QR_BOX - qrPixelHeight) / 2;
            graphics.blit(QR_TEXTURE, qx, qy, qx + qrPixelWidth, qy + qrPixelHeight,
                0.0F, 1.0F, 0.0F, 1.0F);
        }

        graphics.centeredText(this.font, Component.translatable("kazumiplayer.gui.bili_login.hint"),
            this.width / 2, boxY + QR_BOX + 10, 0xFF888888);
        graphics.centeredText(this.font, Component.translatable(statusKey),
            this.width / 2, boxY + QR_BOX + 24, statusColor);
        graphics.centeredText(this.font, Component.translatable("kazumiplayer.gui.bili_login.note"),
            this.width / 2, boxY + QR_BOX + 40, 0xFF777777);
    }

    @Override
    public void onClose() {
        releaseQrTexture();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
