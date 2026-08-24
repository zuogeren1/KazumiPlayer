package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderer;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 全屏观影模式：关闭播放器 GUI 后由 HUD 层接管画面渲染。
 *
 * 输入锁定由 {@link FullscreenOverlayScreen}（透明屏障 Screen）承担：
 * 占住 Screen 槽位即锁死移动/攻击/开背包等游戏输入，且光标自动可见；
 * 聊天 T 由屏障转发给原生聊天，聊天关闭后 tick 自动恢复屏障，画面全程持续。
 *
 * 渲染挂在原版 HOTBAR 层之后：盖住准心/血条等已渲染 HUD，聊天层随后渲染自然浮于其上；
 * 画面区域 = 窗口 × fullscreenCoverage%，居中，整体不透明度 fullscreenOpacity%；
 * 底部进度条与时间绘制在画面下方的预留带内，不遮挡画面；
 * 右上角「退出」按钮点击后返回绑定屏幕的播放器 GUI。
 */
public class ClientFullscreenState {

    private static final int EXIT_BTN_W = 44;
    private static final int EXIT_BTN_H = 16;

    private static boolean active;
    private static BlockPos screenPos;
    /** 进入来源：true=从播放器 GUI 的全屏按钮进入（退出时返回 GUI），false=观影器等直接进入（退出时回世界） */
    private static boolean returnToGui;

    public static boolean isActive() { return active; }

    /** 进入全屏：记录目标屏幕并以透明输入屏障替换当前界面 */
    public static void enter(BlockPos pos, boolean backToGui) {
        screenPos = pos;
        active = true;
        returnToGui = backToGui;
        FullscreenOverlayScreen.open();
    }

    /** 退出全屏：从 GUI 进入的返回该屏幕的播放器 GUI，其余直接回到世界 */
    public static void exit() {
        deactivate();
        var mc = Minecraft.getInstance();
        if (returnToGui) {
            mc.setScreen(new me.zuogeren.kazumiplayer.client.gui.KazumiPlayerScreen(screenPos));
        } else {
            mc.setScreen(null);
        }
    }

    /** 静默清除全屏状态（断线/退出存档时调用，不打开 GUI） */
    public static void deactivate() {
        active = false;
    }

    /**
     * 命中检测：GUI 坐标落在退出按钮上则退出全屏。
     * 供 FullscreenOverlayScreen 的鼠标处理调用。
     */
    public static boolean tryExitAt(double guiX, double guiY) {
        int[] r = exitButtonRect();
        boolean hit = guiX >= r[0] && guiX < r[2] && guiY >= r[1] && guiY < r[3];
        if (hit) exit();
        return hit;
    }

    // ---- 渲染（挂在 HOTBAR 层之后、聊天层之前）----

    @SubscribeEvent
    public static void onHudLayer(RenderGuiLayerEvent.Post event) {
        if (!active || !event.getName().equals(VanillaGuiLayers.HOTBAR)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) { // 断线兜底：世界已卸载则不再绘制
            active = false;
            return;
        }
        var g = event.getGuiGraphics();
        int[] a = areaRect();
        int ax = a[0], ay = a[1], areaW = a[2], areaH = a[3];

        var player = ScreenPlayerManager.getPlayer(screenPos);
        var tex = VideoScreenRenderer.getScreenTexture(screenPos);
        boolean hasSignal = tex != null && tex.hasValidFrame() && player != null
                && player.getWidth() > 0 && player.getHeight() > 0;

        if (hasSignal) {
            // 底部为进度条+时间预留一条带，视频等比缩小并居中于剩余空间，避免条压住画面
            int reservedBand = 16;
            double scale = Math.min(areaW / (double) player.getWidth(),
                Math.max(1, areaH - reservedBand) / (double) player.getHeight());
            int pw = Math.max(1, (int) (player.getWidth() * scale));
            int ph = Math.max(1, (int) (player.getHeight() * scale));
            int px = ax + (areaW - pw) / 2;
            int py = ay + Math.max(0, (areaH - reservedBand - ph)) / 2;
            int alpha = Math.round(255 * ClientConfig.CONFIG.fullscreenOpacity.get() / 100f);
            int color = (alpha << 24) | 0xFFFFFF;
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex.getTextureId(),
                px, py, 0.0F, 0.0F, pw, ph,
                player.getWidth(), player.getHeight(),
                player.getWidth(), player.getHeight(), color);

            drawProgress(g, mc, px, py, pw, ph, player);
        } else {
            g.fill(ax, ay, ax + areaW, ay + areaH, 0xC8101010);
            g.centeredText(mc.font, Component.translatable("kazumiplayer.gui.full.no_signal"), ax + areaW / 2, ay + areaH / 2 - 4, 0xFF888888);
        }

        drawExitButton(g, mc);
    }

    /** 进度条 + 时间（画在视频画面下方的预留带内，不遮挡画面；样式对齐世界内进度条） */
    private static void drawProgress(net.minecraft.client.gui.GuiGraphicsExtractor g, Minecraft mc,
            int px, int py, int pw, int ph, me.zuogeren.kazumiplayer.playback.WaterMediaPlayer player) {
        int barH = 4;
        long dur = player.getDurationMs();
        long cur = Math.max(0, player.getTimeMs());
        int barY = py + ph + 2;
        g.fill(px, barY, px + pw, barY + barH, 0x90555555);
        if (dur > 0) {
            int played = (int) Math.min(pw, (double) pw * cur / dur);
            g.fill(px, barY, px + played, barY + barH, 0xFF44FF33);
        }
        String time = KazumiMessages.formatMs(cur) + (dur > 0 ? " / " + KazumiMessages.formatMs(dur) : "");
        g.text(mc.font, Component.literal(time), px + pw - mc.font.width(time), barY + barH + 1, 0xFFCCCCCC);
    }

    // ---- 退出按钮 ----

    /** 全屏覆盖区域矩形 {x0,y0,w,h}（按窗口尺寸与覆盖度配置），绘制与命中共用 */
    private static int[] areaRect() {
        var mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        int cov = Math.max(1, Math.min(100, ClientConfig.CONFIG.fullscreenCoverage.get()));
        int aw = Math.max(1, gw * cov / 100), ah = Math.max(1, gh * cov / 100);
        return new int[]{(gw - aw) / 2, (gh - ah) / 2, aw, ah};
    }

    /** 退出按钮矩形 {x0,y0,x1,y1}（覆盖区域内右上角），绘制与命中共用 */
    private static int[] exitButtonRect() {
        int[] a = areaRect();
        return new int[]{a[0] + a[2] - EXIT_BTN_W - 4, a[1] + 4,
            a[0] + a[2] - 4, a[1] + 4 + EXIT_BTN_H};
    }

    private static double[] mouseGuiPos(Minecraft mc) {
        double scale = mc.getWindow().getGuiScale();
        return new double[]{mc.mouseHandler.xpos() / scale, mc.mouseHandler.ypos() / scale};
    }

    private static void drawExitButton(net.minecraft.client.gui.GuiGraphicsExtractor g, Minecraft mc) {
        int[] r = exitButtonRect();
        double[] m = mouseGuiPos(mc);
        boolean hover = m[0] >= r[0] && m[0] < r[2] && m[1] >= r[1] && m[1] < r[3];
        g.fill(r[0], r[1], r[2], r[3], hover ? 0xE03C3C52 : 0x90000000);
        String label = Component.translatable("kazumiplayer.gui.full.btn_exit").getString();
        g.text(mc.font, Component.literal(label),
            r[0] + (EXIT_BTN_W - mc.font.width(label)) / 2, r[1] + 4, 0xFFFFFFFF);
    }

    // ---- 状态维护 ----

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!active) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) { // 断线兜底
            active = false;
            return;
        }
        // 聊天等界面关闭后自动恢复输入屏障（exit 已将 active 置 false，不会形成循环）
        if (mc.screen == null) {
            FullscreenOverlayScreen.open();
        }
    }

    /** 断线/退出存档：静默清除全屏状态 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        deactivate();
    }
}
