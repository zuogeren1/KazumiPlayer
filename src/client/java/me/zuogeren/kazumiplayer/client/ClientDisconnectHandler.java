package me.zuogeren.kazumiplayer.client;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.playback.PlaybackManager;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientDisconnectHandler {
    private static int tickCounter;
    private static final Set<VideoScreenBlockEntity> activeScreens = ConcurrentHashMap.newKeySet();

    // ---- 调试：鼠标抓取状态监控（临时诊断用，定位播放时鼠标脱离准心） ----
    private static boolean lastMouseGrabbed = true;
    private static boolean lastWindowActive = true;
    private static boolean lastDesync;
    private static long lastUngrabTick = -1;
    private static int diagTickCounter;
    private static int mouseStuckTicks;

    // GLFW 光标模式常量（与 InputConstants.grabOrReleaseMouse 使用的值一致）
    private static final int GLFW_CURSOR = 208897;
    private static final int GLFW_CURSOR_NORMAL = 212993;
    private static final int GLFW_CURSOR_DISABLED = 212995;

    /**
     * 强制恢复鼠标抓取（供嗅探/播放器等外部释放光标的场景调用）。
     *
     * 直接 grabMouse() 会因内部 mouseGrabbed 仍为 true 而空转（外部释放未同步标志），
     * 必须先 releaseMouse() 复位内部标志再抓取。仅在窗口活跃且无 GUI 时生效。
     */
    public static void forceRestoreMouseGrab(Minecraft mc) {
        if (!mc.isWindowActive() || mc.screen != null) {
            return;
        }
        mc.mouseHandler.releaseMouse();
        mc.mouseHandler.grabMouse();
    }

    @SubscribeEvent
    public static void onClientTickHighFrequency(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            lastMouseGrabbed = true;
            return;
        }
        boolean grabbed = mc.mouseHandler.isMouseGrabbed();
        boolean windowActive = mc.isWindowActive();
        // GLFW 真实光标状态：MCEF/WaterMedia 可能直接操作 GLFW 导致内部标志与实际不一致
        int realCursorMode = org.lwjgl.glfw.GLFW.glfwGetInputMode(
                mc.getWindow().handle(), GLFW_CURSOR);
        boolean realGrabbed = realCursorMode == GLFW_CURSOR_DISABLED;

        // 内部标志与真实状态不同步（鼠标已脱离但 MC 认为仍捕获）→ 状态变化时记录诊断，避免刷屏
        boolean desync = grabbed != realGrabbed;
        if (desync != lastDesync) {
            lastDesync = desync;
            if (desync) {
                KazumiLog.general.debug("mouse state desync: internalGrabbed={} realCursorMode={} windowActive={} screen={}",
                        grabbed, realCursorMode, windowActive,
                        mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
            } else {
                KazumiLog.general.debug("mouse state resync: internalGrabbed={} realCursorMode={}",
                        grabbed, realCursorMode);
            }
        }

        if (windowActive != lastWindowActive) {
            lastWindowActive = windowActive;
            KazumiLog.general.debug("window active changed -> {} (mouseGrabbed={})",
                windowActive, grabbed);
        }
        if (realGrabbed != lastMouseGrabbed) {
            lastMouseGrabbed = realGrabbed;
            if (!realGrabbed) {
                long now = System.currentTimeMillis();
                long sinceLast = lastUngrabTick < 0 ? -1 : now - lastUngrabTick;
                lastUngrabTick = now;
                // 记录释放时上下文：窗口活跃？是否开着 GUI？正在播放的屏幕数
                int playingScreens = (int) activeScreens.stream()
                    .filter(s -> ScreenPlayerManager.getPlayer(s.getBlockPos()) != null).count();
                KazumiLog.general.debug("mouse ungrabbed t={}ms (since last={}ms) windowActive={} screen={} playingScreens={}",
                    now, sinceLast, mc.isWindowActive(),
                    mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(),
                    playingScreens);
            }
        }
        // 防御修复：窗口活跃且无 GUI，但 GLFW 真实光标未捕获
        // （覆盖：MCEF/WaterMedia 直接释放光标、失焦期间 grabMouse() 静默失败导致内部标志与真实状态不同步）
        // → 自动重新捕获，避免"鼠标指针出现、需点击窗口才恢复"
        if (windowActive && mc.screen == null && !realGrabbed) {
            if (++mouseStuckTicks > 2) { // 2 tick = 0.1 秒防抖（条件已足够严格，缩短闪烁时间）
                forceRestoreMouseGrab(mc);
                int after = org.lwjgl.glfw.GLFW.glfwGetInputMode(
                        mc.getWindow().handle(), GLFW_CURSOR);
                KazumiLog.general.debug("auto-restored mouse grab (was stuck {} ticks, internal={}, after={})",
                        mouseStuckTicks, mc.mouseHandler.isMouseGrabbed(), after);
                if (after != GLFW_CURSOR_DISABLED) {
                    KazumiLog.general.warn("grabMouse did not take effect (realCursorMode={}, windowActive={})",
                            after, mc.isWindowActive());
                }
                mouseStuckTicks = 0;
            }
        } else {
            mouseStuckTicks = 0;
        }
        // 周期性状态快照（每 2 秒）：捕获"持续未捕获"但无状态变化的盲区
        if (++diagTickCounter % 40 == 0) {
            int playingScreens = (int) activeScreens.stream()
                .filter(s -> ScreenPlayerManager.getPlayer(s.getBlockPos()) != null).count();
            KazumiLog.general.debug("snapshot internalGrabbed={} realGrabbed={} windowActive={} screen={} playingScreens={}",
                grabbed, realGrabbed, windowActive,
                mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(),
                playingScreens);
        }
    }

    // ---- 命令（EVENT_BUS） ----

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        var d = event.getDispatcher();
        d.register(Commands.literal("krule")
            .then(Commands.literal("test")
                .executes(ctx -> { testAllRules(); return 1; })
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests((ctx, b) -> { ClientRuleCache.listAll().forEach(b::suggest); return b.buildFuture(); })
                    .executes(ctx -> { testOneRule(StringArgumentType.getString(ctx, "name")); return 1; }))));
    }

    private static final RuleEngine ruleEngine = new RuleEngine();

    private static void testAllRules() {
        var names = ClientRuleCache.listAll();
        if (names.isEmpty()) { chat("§c无规则，请先执行 /kazumi rule pull-all"); return; }
        chat("§e测试 " + names.size() + " 个规则...");
        for (String name : names) {
            Rule rule = ClientRuleCache.get(name);
            if (rule == null) continue;
            String n = name;
            long t0 = System.currentTimeMillis();
            ruleEngine.search(rule, "test")
                .thenAccept(r -> chat("§a" + n + " §7" + (System.currentTimeMillis() - t0) + "ms"))
                .exceptionally(e -> { chat("§c" + n + " §7失败"); return null; });
        }
    }

    private static void testOneRule(String name) {
        Rule rule = ClientRuleCache.get(name);
        if (rule == null) { chat("§c规则不存在: " + name); return; }
        chat("§e测试 " + name + " ...");
        long t0 = System.currentTimeMillis();
        ruleEngine.search(rule, "test")
            .thenAccept(r -> chat("§a" + name + " §7" + (System.currentTimeMillis() - t0) + "ms §7" + r.items().size() + "条"))
            .exceptionally(e -> { chat("§c" + name + " §7失败: " + e.getMessage()); return null; });
    }

    private static void chat(String msg) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal(msg));
    }

    // ---- 生命周期（EVENT_BUS） ----

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAllActive();
    }

    private static boolean isWatching(VideoScreenBlockEntity screen, Minecraft mc) {
        if (mc.player == null) return false;
        String watchers = screen.getWatchingPlayers();
        if (watchers.isEmpty()) return false;
        return java.util.Arrays.asList(watchers.split(","))
            .contains(mc.player.getUUID().toString());
    }

    private static void stopAllActive() {
        for (var screen : activeScreens) {
            ScreenPlayerManager.remove(screen.getBlockPos());
        }
        activeScreens.clear();
        ScreenPlayerManager.stopAll();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter % 20 != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // 离开世界时停止所有播放
            stopAllActive();
            return;
        }
        // 兜底：屏幕方块已被移除但播放器残留（如 PlayStopPacket 丢失）→ 停止并清理
        var iter = activeScreens.iterator();
        while (iter.hasNext()) {
            var screen = iter.next();
            if (screen.isRemoved()) {
                var orphan = ScreenPlayerManager.getPlayer(screen.getBlockPos());
                if (orphan != null) {
                    orphan.stop();
                }
                ScreenPlayerManager.remove(screen.getBlockPos());
                iter.remove();
                KazumiLog.playback.info("Cleaned up orphan playback at removed screen {}", screen.getBlockPos());
            }
        }
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof VideoScreenBlockEntity screen) {
                String url = screen.getEpisodeUrl();
                var sp = ScreenPlayerManager.get(screen.getBlockPos());
                // 新播放：启动播放器并预置 seek
                // 只有 WatchingPlayers 中的玩家才自动播放（手动 join 后才能播）
                if (sp.player == null && !url.isEmpty() && isWatching(screen, mc)
                        && System.currentTimeMillis() - sp.playbackStartedAt > 3000) {
                    sp.playbackStartedAt = System.currentTimeMillis();
                    PlaybackManager pm = new PlaybackManager();
                    pm.playUrl(screen, url);
                    sp.player = pm.getWaterMedia();
                    activeScreens.add(screen);
                    sp.lastEpisodeUrl = url;
                    sp.endedNotified = true; // 防止新播放器初始化期间误触发 isEnded()
                    long seekMs = screen.getSyncPositionMs();
                    if (seekMs > 0) sp.player.seek(seekMs);
                    // 启动快照：屏幕处于暂停时立即暂停（不再依赖每秒轮询）
                    if (screen.isPlaybackPaused()) {
                        sp.player.pause();
                    }
                }
                // 已启动但 seek 未生效：等播放器就绪后重试
                if (sp.player != null && sp.player.hasPendingSeek() && sp.player.isPlaying()) {
                    sp.player.applyPendingSeek();
                }
                // 音量（暂停/恢复状态由 SyncStatePacket 推送，不再每秒轮询 NBT）
                if (sp.player != null) {
                    sp.player.applyVolumeFromOptions();
                }
                // 检测播放完毕 → 自动下一集
                if (sp.player != null && sp.player.isEnded() && !sp.endedNotified) {
                    sp.endedNotified = true;
                    KazumiLog.playback.info("Auto-next: ended detected at screen {}", screen.getBlockPos());
                    var pkt = new NextEpisodePacket(screen.getBlockPos());
                    mc.getConnection().send(new ServerboundCustomPayloadPacket(pkt));
                }
                // URL 变了 → 停旧播放器，下次 tick 自动启动新的
                if (!url.isEmpty() && !url.equals(sp.lastEpisodeUrl)) {
                    if (sp.player != null) {
                        sp.player.stop();
                        sp.player = null;
                    }
                    sp.lastEpisodeUrl = url;
                    sp.endedNotified = false;
                }
            }
        }
        // 音响 tick（漂移校正、屏幕连接检查）
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity spk) {
                SpeakerClientAudio.tick(spk);
            }
        }
    }
}
