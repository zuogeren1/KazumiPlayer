package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.network.packet.PlaybackAction;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenRenderer;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
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
 * 底部悬浮控制条：暂停/继续、±10s、可拖动进度条与时间（seek 经服务端 forceSeek 定向指令
 * 全组即时生效），直播直连屏整体停用；
 * 快捷键：空格暂停/继续、←/→ ±10s、↑/↓ RECORDS 总音量（带瞬时 OSD）；
 * 控制条与右上角「退出」按钮共用鼠标静止 3s 淡出的显隐策略，淡出后不响应命中。
 */
public class ClientFullscreenState {

    private static final int EXIT_BTN_W = 44;
    private static final int EXIT_BTN_H = 16;

    // ---- 控制条几何 ----
    private static final int CTL_BAR_H = 20;
    private static final int CTL_BTN_H = 14;
    private static final int PAUSE_BTN_W = 26;
    private static final int SEEK_BTN_W = 32;
    private static final int BAR_PAD = 6;
    private static final int PROGRESS_H = 5;
    /** 拖动进度条期间的目标位置；-1 = 未在拖动 */
    private static boolean progressDragging;
    private static double dragTargetMs = -1;
    /** 瞬时提示（音量等）：文本 + 失效时刻 */
    private static String osdText;
    private static long osdUntil;
    private static long lastPauseToggleAt;

    /** 鼠标静止此时长后开始淡出（控制条/退出按钮共用同一显隐策略） */
    private static final long CONTROLS_IDLE_DELAY_MS = 3000;
    /** 淡出动效时长：从完全不透明渐变到全透明 */
    private static final long CONTROLS_FADE_MS = 500;
    private static long lastMouseMoveAt;
    private static double lastMouseX = -1, lastMouseY = -1;

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
        lastMouseMoveAt = System.currentTimeMillis(); // 进入即显示控件，避免初值导致秒隐
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
        if (controlsAlpha(System.currentTimeMillis()) <= 0.05f) return false; // 已淡出不可点
        int[] r = exitButtonRect();
        boolean hit = guiX >= r[0] && guiX < r[2] && guiY >= r[1] && guiY < r[3];
        if (hit) exit();
        return hit;
    }

    // ---- 控制动作与命中（供 FullscreenOverlayScreen 的键鼠事件调用）----

    private static boolean hit(int[] r, double x, double y) {
        return x >= r[0] && x < r[2] && y >= r[1] && y < r[3];
    }

    /** 控制条点击分发：按钮/进度条命中返回 true（已消费）；控制条淡出或直播屏时整体不响应 */
    public static boolean handleControlClick(double x, double y) {
        var player = ScreenPlayerManager.getPlayer(screenPos);
        if (player == null || isLiveStream()) return false;
        if (controlsAlpha(System.currentTimeMillis()) <= 0.05f) return false;
        int[][] btns = controlButtonRects();
        if (hit(btns[0], x, y)) { togglePause(); return true; }
        if (hit(btns[1], x, y)) { seekBy(-10); return true; }
        if (hit(btns[2], x, y)) { seekBy(10); return true; }
        int[] pb = progressBarRect(Minecraft.getInstance(), player);
        if (pb != null && hit(pb, x, y)) {
            progressDragging = true;
            dragTargetMs = -1;
            updateSeekDrag(x);
            return true;
        }
        return false;
    }

    /** 拖动进度条：仅更新本地目标位置显示，松手时一次性提交 seek */
    public static void updateSeekDrag(double x) {
        if (!progressDragging) return;
        var player = ScreenPlayerManager.getPlayer(screenPos);
        if (player == null || player.getDurationMs() <= 0) { progressDragging = false; return; }
        int[] pb = progressBarRect(Minecraft.getInstance(), player);
        if (pb == null) { progressDragging = false; return; }
        double ratio = Math.max(0.0, Math.min(1.0, (x - pb[0]) / (double) (pb[2] - pb[0])));
        dragTargetMs = ratio * player.getDurationMs();
    }

    /** 松手提交拖动目标：走 SEEK_GOTO，经服务端 forceSeek 定向指令全组即时生效 */
    public static void finishSeekDrag() {
        if (!progressDragging) return;
        progressDragging = false;
        if (dragTargetMs >= 0 && isFiniteTarget(dragTargetMs)) {
            sendControl(PlaybackAction.SEEK_GOTO, (long) dragTargetMs);
        }
        dragTargetMs = -1;
    }

    private static boolean isFiniteTarget(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** 空格：暂停/继续（按键重复防抖；直播直连屏忽略） */
    public static void togglePause() {
        if (isLiveStream()) return;
        long now = System.currentTimeMillis();
        if (now - lastPauseToggleAt < 250) return;
        lastPauseToggleAt = now;
        var player = ScreenPlayerManager.getPlayer(screenPos);
        sendControl(player != null && player.isPlaying() ? PlaybackAction.PAUSE : PlaybackAction.RESUME, 0);
    }

    /** ←/→ 或 ±10s 按钮：相对 seek，经服务端 forceSeek 定向指令全组即时生效 */
    public static void seekBy(int seconds) {
        if (isLiveStream()) return;
        sendControl(seconds >= 0 ? PlaybackAction.SEEK_FORWARD : PlaybackAction.SEEK_BACK, Math.abs(seconds));
    }

    /**
     * ↑/↓：调节本屏独立音量 ±5%（升音自动解除静音）；RECORDS 滑块为全局总控，不受影响。
     * 直播直连屏时间轴类操作无意义故停用，但音量是本地行为，↑↓/M 保留可用。
     */
    public static void adjustVolume(int deltaPercent) {
        var sp = ScreenPlayerManager.get(screenPos);
        if (deltaPercent > 0 && sp.muted) {
            sp.muted = false;
        } else if (!sp.muted) {
            sp.volumeScale = Math.max(0f, Math.min(1f, sp.volumeScale + deltaPercent / 100f));
        }
        applyLocalVolume();
        showVolumeOsd(sp);
    }

    /** M：本屏静音开关（解除后回到本屏音量系数） */
    public static void toggleMute() {
        var sp = ScreenPlayerManager.get(screenPos);
        sp.muted = !sp.muted;
        applyLocalVolume();
        showVolumeOsd(sp);
    }

    private static void applyLocalVolume() {
        var player = ScreenPlayerManager.getPlayer(screenPos);
        var sp = ScreenPlayerManager.get(screenPos);
        if (player != null) player.applyVolumeFromOptions(sp.muted ? 0f : sp.volumeScale);
    }

    private static void showVolumeOsd(ScreenPlayerManager.ScreenPlayer sp) {
        osdText = Component.translatable(sp.muted
                ? "kazumiplayer.gui.full.mute_osd"
                : "kazumiplayer.gui.full.volume_osd",
            String.valueOf(Math.round((sp.muted ? 0f : sp.volumeScale) * 100))).getString();
        osdUntil = System.currentTimeMillis() + 1500;
    }

    private static void sendControl(PlaybackAction action, long value) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundCustomPayloadPacket(
            new PlaybackControlPacket(screenPos, action, value)));
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
            // 底部为控制条预留一条带，视频等比缩小并居中于剩余空间，避免条压住画面
            int reservedBand = CTL_BAR_H;
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
            // 弹幕层：全屏期由 HUD 独占 Store 出队（防双消费），GUI 按钮与观影器两条进入路径同此绘制
            me.zuogeren.kazumiplayer.client.danmaku.DanmakuHudLayer.draw(g, mc, screenPos, px, py, pw, ph);
        } else {
            g.fill(ax, ay, ax + areaW, ay + areaH, 0xC8101010);
            g.centeredText(mc.font, Component.translatable("kazumiplayer.gui.full.no_signal"), ax + areaW / 2, ay + areaH / 2 - 4, 0xFF888888);
        }

        // 控制条：仅正常媒体显示（直播直连无稳定时间轴，暂停/seek 无意义）
        if (hasSignal && !isLiveStream()) {
            drawControls(g, mc);
            drawBufferingHint(g, mc);
        }
        drawStartupStatus(g, mc);
        drawOsd(g, mc); // 音量/静音反馈：直播屏无控制条但快捷键仍可用，须独立于控制条绘制
        drawExitButton(g, mc);
    }

    /** 起播等待/最近失败的状态文字（画面中央，独立于控制条淡出） */
    private static void drawStartupStatus(GuiGraphicsExtractor g, Minecraft mc) {
        var sp = ScreenPlayerManager.get(screenPos);
        int[] a = areaRect();
        long now = System.currentTimeMillis();
        if (!sp.bypassSync && sp.player != null && !sp.everPlayed) {
            long sec = Math.max(0, (now - sp.playbackStartedAt) / 1000);
            String t = Component.translatable("kazumiplayer.gui.main.startup_status",
                String.valueOf(sec)).getString();
            g.text(mc.font, Component.literal(t),
                a[0] + (a[2] - mc.font.width(t)) / 2, a[1] + a[3] / 2 - 16, 0xFFCCCCCC);
            var be = mc.level != null && mc.level.getBlockEntity(screenPos)
                instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity vs ? vs : null;
            if (be != null && !be.getEpisodeData().isEmpty()
                    && !me.zuogeren.kazumiplayer.util.DirectLinkQueue.isQueueData(be.getEpisodeData())) {
                String ep = Component.translatable("kazumiplayer.gui.main.startup_episode",
                    String.valueOf(be.getEpisodeIndex())).getString();
                g.text(mc.font, Component.literal(ep),
                    a[0] + (a[2] - mc.font.width(ep)) / 2, a[1] + a[3] / 2, 0xFF667788);
            }
        } else if (!sp.bypassSync && sp.player == null && sp.lastFailedAt > 0
                && now - sp.lastFailedAt < 20000) {
            // 与 GUI 横幅同一 URL 守卫：换集后 player 短暂为空的间隙不闪现上一集的失败横幅
            var be = mc.level != null && mc.level.getBlockEntity(screenPos)
                instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity vs ? vs : null;
            if (be == null || !be.getEpisodeUrl().equals(sp.lastFailedUrl)) return;
            String t = Component.translatable("kazumiplayer.gui.main.fail_banner").getString();
            g.text(mc.font, Component.literal(t),
                a[0] + (a[2] - mc.font.width(t)) / 2, a[1] + a[3] / 2 - 16, 0xFFFF8888);
        }
    }

    /** 重缓冲冻结提示：进度条上方居中琥珀文字（独立于控制条淡出，冻结期间恒可见） */
    private static void drawBufferingHint(GuiGraphicsExtractor g, Minecraft mc) {
        var sp = ScreenPlayerManager.get(screenPos);
        if (!sp.isBufferingFrozen() || progressDragging) return;
        var player = ScreenPlayerManager.getPlayer(screenPos);
        if (player == null) return;
        int[] pb = progressBarRect(mc, player);
        if (pb == null) return;
        String buf = Component.translatable("kazumiplayer.gui.full.buffering").getString();
        g.text(mc.font, Component.literal(buf),
            (pb[0] + pb[2] - mc.font.width(buf)) / 2, pb[1] - 11, 0xFFFFC060);
    }

    // ---- 控制条（暂停/±10s/可拖动进度条/时间；显隐与退出按钮共用静止淡出策略）----

    /** 直播直连屏判定：对齐 GUI 的 liveCtl 语义，控制条与快捷键整体停用 */
    private static boolean isLiveStream() {
        var sp = ScreenPlayerManager.get(screenPos);
        return sp == null || sp.bypassSync;
    }

    /** 控制条矩形 {x,y,w,h}：覆盖区域底部整宽 */
    private static int[] controlBarRect() {
        int[] a = areaRect();
        return new int[]{a[0], a[1] + a[3] - CTL_BAR_H, a[2], CTL_BAR_H};
    }

    /** 三枚按钮矩形 {pause, back, forward}，绘制与命中共用 */
    private static int[][] controlButtonRects() {
        int[] bar = controlBarRect();
        int by = bar[1] + (CTL_BAR_H - CTL_BTN_H) / 2;
        int bx = bar[0] + BAR_PAD;
        return new int[][]{
            {bx, by, bx + PAUSE_BTN_W, by + CTL_BTN_H},
            {bx + PAUSE_BTN_W + 4, by, bx + PAUSE_BTN_W + 4 + SEEK_BTN_W, by + CTL_BTN_H},
            {bx + PAUSE_BTN_W + 8 + SEEK_BTN_W, by, bx + PAUSE_BTN_W + 8 + SEEK_BTN_W * 2, by + CTL_BTN_H}
        };
    }

    private static String timeText(WaterMediaPlayer player) {
        long dur = player.getDurationMs();
        long cur = Math.max(0, player.getTimeMs());
        return KazumiMessages.formatMs(cur) + (dur > 0 ? " / " + KazumiMessages.formatMs(dur) : "");
    }

    /**
     * 进度条矩形 {x0,y0,x1,y1}：位于按钮与时间文本之间；拖动时以 dragTargetMs 为准。
     * 无播放器/无时长/区间过窄返回 null（不可拖）。
     */
    private static int[] progressBarRect(Minecraft mc, WaterMediaPlayer player) {
        int[] bar = controlBarRect();
        String time = progressDragging
            ? KazumiMessages.formatMs(Math.max(0, (long) dragTargetMs)) + " / " + KazumiMessages.formatMs(player.getDurationMs())
            : timeText(player);
        int x0 = bar[0] + BAR_PAD + PAUSE_BTN_W + 8 + SEEK_BTN_W * 2 + 8;
        int x1 = bar[0] + bar[2] - BAR_PAD - mc.font.width(time) - 8;
        if (x1 - x0 < 40) return null;
        int yBar = bar[1] + (CTL_BAR_H - PROGRESS_H) / 2;
        return new int[]{x0, yBar, x1, yBar + PROGRESS_H};
    }

    private static void drawControls(GuiGraphicsExtractor g, Minecraft mc) {
        float alpha = controlsAlpha(System.currentTimeMillis());
        if (alpha <= 0f) return;
        double[] m = trackMouse(mc);
        var player = ScreenPlayerManager.getPlayer(screenPos);
        if (player == null) return;

        int[] bar = controlBarRect();
        g.fill(bar[0], bar[1], bar[0] + bar[2], bar[1] + bar[3], fadeAlpha(0xB0000000, alpha));

        boolean playing = player.isPlaying();
        int[][] btns = controlButtonRects();
        drawCtlButton(g, mc, btns[0], playing
                ? Component.translatable("kazumiplayer.gui.main.btn_pause").getString()
                : Component.translatable("kazumiplayer.gui.main.btn_play").getString(),
            m, alpha);
        drawCtlButton(g, mc, btns[1], "-10s", m, alpha);
        drawCtlButton(g, mc, btns[2], "+10s", m, alpha);

        int[] pb = progressBarRect(mc, player);
        if (pb != null) {
            long dur = player.getDurationMs();
            long cur = progressDragging ? Math.max(0, (long) dragTargetMs)
                : Math.max(0, player.getTimeMs());
            g.fill(pb[0], pb[1], pb[2], pb[3], fadeAlpha(0x90555555, alpha));
            if (dur > 0) {
                int played = (int) Math.min(pb[2] - pb[0], (pb[2] - pb[0]) * cur / dur);
                g.fill(pb[0], pb[1], pb[0] + played, pb[3], fadeAlpha(0xFF44FF33, alpha));
            }
            if (progressDragging) { // 拖动中的目标位置游标
                g.fill(pb[0] + playedClamp(pb, cur, dur) - 1, pb[1] - 2,
                    pb[0] + playedClamp(pb, cur, dur) + 1, pb[3] + 2, fadeAlpha(0xFFFFFFFF, alpha));
            }
        }

        String time = progressDragging
            ? KazumiMessages.formatMs(Math.max(0, (long) dragTargetMs)) + " / " + KazumiMessages.formatMs(player.getDurationMs())
            : timeText(player);
        g.text(mc.font, Component.literal(time),
            bar[0] + bar[2] - BAR_PAD - mc.font.width(time),
            bar[1] + (CTL_BAR_H - mc.font.lineHeight) / 2, fadeAlpha(0xFFCCCCCC, alpha));
    }

    /** 拖动游标的横向钳制（进度条内白线） */
    private static int playedClamp(int[] pb, long cur, long dur) {
        if (dur <= 0) return pb[0];
        return pb[0] + (int) Math.min(pb[2] - pb[0], (pb[2] - pb[0]) * cur / dur);
    }

    private static void drawCtlButton(GuiGraphicsExtractor g, Minecraft mc, int[] r,
            String label, double[] mouse, float alpha) {
        boolean hover = mouse[0] >= r[0] && mouse[0] < r[2] && mouse[1] >= r[1] && mouse[1] < r[3];
        g.fill(r[0], r[1], r[2], r[3], fadeAlpha(hover ? 0xE03C3C52 : 0x66333344, alpha));
        g.text(mc.font, Component.literal(label),
            r[0] + Math.max(1, (r[2] - r[0] - mc.font.width(label)) / 2),
            r[1] + (CTL_BTN_H - mc.font.lineHeight) / 2, fadeAlpha(0xFFFFFFFF, alpha));
    }

    /** 瞬时提示绘制（音量/静音反馈）：超时自动消失；独立于控制条淡出，直播屏亦可见 */
    private static void drawOsd(GuiGraphicsExtractor g, Minecraft mc) {
        if (osdText == null || System.currentTimeMillis() >= osdUntil) return;
        int[] a = areaRect();
        g.text(mc.font, Component.literal(osdText),
            a[0] + (a[2] - mc.font.width(osdText)) / 2,
            a[1] + a[3] - CTL_BAR_H - 14, 0xFFFFFF55);
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

    /**
     * 控件可见度因子（0=全透明，1=不透明）：鼠标移动即恢复全显，
     * 静止 CONTROLS_IDLE_DELAY_MS 后在 CONTROLS_FADE_MS 内线性淡出。
     * 退出按钮与控制条共用同一显隐策略；拖动进度条期间豁免淡出保持常显。
     */
    private static float controlsAlpha(long now) {
        if (progressDragging) return 1f;
        long idle = now - lastMouseMoveAt;
        if (idle <= CONTROLS_IDLE_DELAY_MS) return 1f;
        return Math.max(0f, 1f - (idle - CONTROLS_IDLE_DELAY_MS) / (float) CONTROLS_FADE_MS);
    }

    /** 逐帧跟踪鼠标位置：有移动即刷新静止计时（HUD 每帧调用，无需事件钩子） */
    private static double[] trackMouse(Minecraft mc) {
        double[] m = mouseGuiPos(mc);
        long now = System.currentTimeMillis();
        if (m[0] != lastMouseX || m[1] != lastMouseY) {
            lastMouseX = m[0];
            lastMouseY = m[1];
            lastMouseMoveAt = now;
        }
        return m;
    }

    /** ARGB 颜色按因子衰减 alpha 通道（RGB 不变） */
    private static int fadeAlpha(int argb, float factor) {
        int a = (int) ((argb >>> 24) * factor);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static void drawExitButton(net.minecraft.client.gui.GuiGraphicsExtractor g, Minecraft mc) {
        long now = System.currentTimeMillis();
        double[] m = trackMouse(mc);
        float alpha = controlsAlpha(now);
        if (alpha <= 0f) return; // 完全淡出后不绘制
        int[] r = exitButtonRect();
        boolean hover = m[0] >= r[0] && m[0] < r[2] && m[1] >= r[1] && m[1] < r[3];
        g.fill(r[0], r[1], r[2], r[3], fadeAlpha(hover ? 0xE03C3C52 : 0x90000000, alpha));
        String label = Component.translatable("kazumiplayer.gui.full.btn_exit").getString();
        g.text(mc.font, Component.literal(label),
            r[0] + (EXIT_BTN_W - mc.font.width(label)) / 2, r[1] + 4,
            fadeAlpha(0xFFFFFFFF, alpha));
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
