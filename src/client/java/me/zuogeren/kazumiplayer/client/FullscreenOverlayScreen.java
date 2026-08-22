package me.zuogeren.kazumiplayer.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 全屏观影的透明输入屏障：占住 Screen 槽位即锁死游戏输入（移动/攻击/使用/开背包等），
 * 自身不渲染任何内容（画面由 ClientFullscreenState 的 HUD 层负责，本类仅承接输入）。
 * T 键转发给原生聊天（聊天关闭后由 tick 自动恢复本屏障）；Esc 或点击画面右上角退出按钮退出全屏。
 */
public class FullscreenOverlayScreen extends Screen {

    /** 是否在下一个 tick 打开聊天（须延迟：同帧稍后的字符事件 't' 才不会落进聊天输入框） */
    private boolean openChatNextTick;

    public FullscreenOverlayScreen() {
        super(Component.literal("KazumiPlayer Fullscreen"));
    }

    /** 打开/恢复输入屏障 */
    public static void open() {
        Minecraft.getInstance().setScreen(new FullscreenOverlayScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 不暂停单人世界，视频与同步继续
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 空渲染：不调 super 以避免任何背景绘制，视觉完全交给 HUD 层
    }

    /**
     * 完全屏蔽默认背景（模糊标记 + 菜单暗纹 + 半透明渐变都会让画面变色/变灰，
     * 且默认实现会调用每帧仅允许一次的 blurBeforeThisStratum 与 HUD 层冲突）。
     * 没有任何「模糊后」元素时 GuiRenderer 整段跳过 processBlurEffect，画面保持清晰。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    }

    @Override
    public void tick() {
        if (openChatNextTick) {
            // 此刻本帧的字符事件 't' 已被本屏障吞掉，打开聊天不会再带入多余字符
            openChatNextTick = false;
            Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen("", true));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_T) {
            if (!openChatNextTick) openChatNextTick = true;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            ClientFullscreenState.exit();
            return true;
        }
        return true; // 其余按键一律吞掉，保持纯观影锁定
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (ClientFullscreenState.tryExitAt(event.x(), event.y())) return true;
        return true; // 吞掉全部点击，不下发给世界
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc 行为由 keyPressed 自定义
    }

    @Override
    public void onClose() {
        ClientFullscreenState.exit();
    }
}
