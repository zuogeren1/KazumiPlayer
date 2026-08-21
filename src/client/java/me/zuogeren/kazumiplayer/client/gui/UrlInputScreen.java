package me.zuogeren.kazumiplayer.client.gui;

import me.zuogeren.kazumiplayer.network.packet.PlayUrlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * 屏幕方块的 URL 输入界面（空手右键屏幕打开）。
 * 聊天栏输入长链接会被截断，GUI 输入框支持粘贴完整链接（上限 2048 字符）。
 */
public class UrlInputScreen extends Screen {
    private static final int MAX_URL_LENGTH = 2048;

    private final BlockPos screenPos;
    private EditBox urlEdit;
    private Button playButton;

    public UrlInputScreen(BlockPos screenPos) {
        super(Component.literal("输入视频链接"));
        this.screenPos = screenPos;
    }

    @Override
    protected void init() {
        this.urlEdit = new EditBox(this.font, this.width / 2 - 150, this.height / 2 - 10, 300, 20,
            Component.literal("视频链接"));
        this.urlEdit.setMaxLength(MAX_URL_LENGTH);
        this.urlEdit.setResponder(value -> this.playButton.active = !value.isBlank());
        this.addRenderableWidget(this.urlEdit);

        this.playButton = this.addRenderableWidget(
            Button.builder(Component.literal("播放"), button -> this.onPlay())
                .bounds(this.width / 2 - 150, this.height / 2 + 16, 145, 20)
                .build());
        this.playButton.active = false;
        this.addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                .bounds(this.width / 2 + 5, this.height / 2 + 16, 145, 20)
                .build());
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.urlEdit);
    }

    @Override
    public void resize(int width, int height) {
        String oldUrl = this.urlEdit.getValue();
        this.init(width, height);
        this.urlEdit.setValue(oldUrl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.playButton.active && this.getFocused() == this.urlEdit && event.isConfirmation()) {
            this.onPlay();
            return true;
        }
        return super.keyPressed(event);
    }

    private void onPlay() {
        String url = this.urlEdit.getValue().trim();
        if (url.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundCustomPayloadPacket(new PlayUrlPacket(this.screenPos, url)));
        }
        this.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, -1);
        graphics.text(this.font, Component.literal("仅支持视频直链，请粘贴直链地址:"),
            this.width / 2 - 150, this.height / 2 - 26, -6250336);
    }
}
