package me.zuogeren.kazumiplayer.client;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
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

    private static int mouseStuckTicks;

    /** 注册正在播放的屏幕（供移除兜底清理） */
    public static void trackScreen(VideoScreenBlockEntity screen) {
        activeScreens.add(screen);
    }

    /** 取消跟踪（停止播放/屏幕移除时调用） */
    public static void untrackScreen(VideoScreenBlockEntity screen) {
        activeScreens.remove(screen);
    }

    // GLFW 光标模式常量（与 InputConstants.grabOrReleaseMouse 使用的值一致）
    private static final int GLFW_CURSOR = 208897;
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
            mouseStuckTicks = 0;
            return;
        }
        boolean windowActive = mc.isWindowActive();

        // 防御修复：窗口活跃且无 GUI，但 GLFW 真实光标未捕获
        // （覆盖：MCEF/WaterMedia 直接释放光标、失焦期间 grabMouse() 静默失败导致内部标志与真实状态不同步）
        // → 自动重新捕获，避免"鼠标指针出现、需点击窗口才恢复"
        if (windowActive && mc.screen == null) {
            int realCursorMode = org.lwjgl.glfw.GLFW.glfwGetInputMode(
                    mc.getWindow().handle(), GLFW_CURSOR);
            if (realCursorMode != GLFW_CURSOR_DISABLED) {
                if (++mouseStuckTicks > 2) { // 2 tick = 0.1 秒防抖
                    forceRestoreMouseGrab(mc);
                    mouseStuckTicks = 0;
                }
            } else {
                mouseStuckTicks = 0;
            }
        } else {
            mouseStuckTicks = 0;
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
        if (names.isEmpty()) { KazumiMessages.chatError("无规则，请先执行 /kazumi rule pull-all"); return; }
        KazumiMessages.chatInfo("测试 " + names.size() + " 个规则...");
        for (String name : names) {
            Rule rule = ClientRuleCache.get(name);
            if (rule == null) continue;
            String n = name;
            long t0 = System.currentTimeMillis();
            ruleEngine.search(rule, "test")
                .thenAccept(r -> KazumiMessages.chatSuccess(n + " §7" + (System.currentTimeMillis() - t0) + "ms"))
                .exceptionally(e -> { KazumiMessages.chatError(n + " 失败"); return null; });
        }
    }

    private static void testOneRule(String name) {
        Rule rule = ClientRuleCache.get(name);
        if (rule == null) { KazumiMessages.chatError("规则不存在: " + name); return; }
        if (rule.isDeprecated()) {
            KazumiMessages.chatWarn("警告: 规则 " + name + " 已被官方标记为已弃用 (deprecated)，可能已失效");
        }
        KazumiMessages.chatInfo("测试 " + name + " ...");
        long t0 = System.currentTimeMillis();
        ruleEngine.search(rule, "test")
            .thenAccept(r -> KazumiMessages.chatSuccess(name + " §7" + (System.currentTimeMillis() - t0) + "ms §7" + r.items().size() + "条"))
            .exceptionally(e -> { KazumiMessages.chatError(name + " 失败: " + e.getMessage()); return null; });
    }

    // ---- 生命周期（EVENT_BUS） ----

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAllActive();
    }

    /** 单机世界退出：集成服务端停止时兜底停止所有播放（客户端连远程服时由 LoggingOut 兜底） */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        // 事件在服务端线程触发，调度到渲染线程执行（WaterMedia 播放器需主线程操作）
        Minecraft.getInstance().execute(ClientDisconnectHandler::stopAllActive);
    }

    private static boolean isWatching(VideoScreenBlockEntity screen, Minecraft mc) {
        if (mc.player == null) return false;
        String watchers = screen.getWatchingPlayers();
        if (watchers.isEmpty()) return false;
        return java.util.Arrays.asList(watchers.split(","))
            .contains(mc.player.getUUID().toString());
    }

    private static void stopAllActive() {
        // 先统一 stop 所有播放器，再清空跟踪集合（remove 也会 stop，但显式 stopAll 保证顺序）
        ScreenPlayerManager.stopAll();
        activeScreens.clear();
        SpeakerClientAudio.stopAll();
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
        // 全面兜底：ScreenPlayerManager 中存在播放器但对应屏幕 BE 已不存在/已停止播放 → 停止并清理
        for (var sp : ScreenPlayerManager.getAll().entrySet()) {
            var be = mc.level.getBlockEntity(sp.getKey());
            boolean screenGone = !(be instanceof VideoScreenBlockEntity screen)
                    || screen.isRemoved();
            boolean noLongerWatching = be instanceof VideoScreenBlockEntity screen
                    && screen.getEpisodeUrl().isEmpty();
            if (sp.getValue().player != null && (screenGone || noLongerWatching)) {
                sp.getValue().player.stop();
                sp.getValue().player = null;
                ScreenPlayerManager.remove(sp.getKey());
                KazumiLog.playback.info("Cleaned up stale playback at {}", sp.getKey());
            } else if (sp.getValue().player == null && sp.getValue().lastEpisodeUrl.isEmpty()) {
                // 空闲残留条目（从未播放/已停止的占位）→ 移除，防止注册表无限增长
                ScreenPlayerManager.remove(sp.getKey());
            }
        }
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof VideoScreenBlockEntity screen) {
                String url = screen.getEpisodeUrl();
                var sp = ScreenPlayerManager.get(screen.getBlockPos());
                // 僵尸播放器自愈：解析失败/租约放弃/嗅探超时耗尽后，播放器已登记但永不进入播放态，
                // 而重试条件是 player == null——不回收就永远卡死（只能离开重新加入才能恢复）。
                // 阈值覆盖两次嗅探超时重试（默认 30s×2）+ 起播缓冲；暂停中的屏幕不算僵尸
                long zombieTimeoutMs = Math.max(75_000,
                    me.zuogeren.kazumiplayer.ClientConfig.CONFIG.sniffTimeoutSeconds.get() * 2000L + 15_000);
                if (sp.player != null && !sp.everPlayed && !screen.isPlaybackPaused()
                        && System.currentTimeMillis() - sp.playbackStartedAt > zombieTimeoutMs) {
                    KazumiLog.playback.warn("Zombie playback (never started) at {}, recycling",
                        screen.getBlockPos());
                    sp.player.stop();
                    sp.player = null;
                    sp.everPlayed = false;
                }
                // 新播放：启动播放器并预置 seek
                // 只有 WatchingPlayers 中的玩家才自动播放（手动 join 后才能播）
                if (sp.player == null && !url.isEmpty() && isWatching(screen, mc)
                        && System.currentTimeMillis() - sp.playbackStartedAt > 3000) {
                    sp.playbackStartedAt = System.currentTimeMillis();
                    sp.player = me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver
                        .getInstance().beginPlayback(screen, url);
                    activeScreens.add(screen);
                    sp.lastEpisodeUrl = url;
                    sp.endedNotified = true; // 防止新播放器初始化期间误触发 isEnded()
                    sp.everPlayed = false;
                    long seekMs = screen.getSyncPositionMs();
                    if (sp.player != null && seekMs > 0) sp.player.seek(seekMs);
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
                if (sp.player != null) {
                    // 开播即解除 L208 的初始化保护——否则 endedNotified 恒为 true，
                    // 播放自然结束后永远无法触发自动下一集
                    if (sp.player.isPlaying()) {
                        sp.endedNotified = false;
                        sp.everPlayed = true; // 已实际出画面，不参与僵尸自愈判定
                    }
                    // 兜底：部分流（live 型 HLS）永不产生 EOF，用时长逼近视为播完
                    boolean ended = sp.player.isEnded()
                        || (sp.player.getDurationMs() > 0
                            && sp.player.getTimeMs() >= sp.player.getDurationMs() - 300);
                    if (ended && !sp.endedNotified) {
                        sp.endedNotified = true;
                        KazumiLog.playback.info("Auto-next: ended detected at screen {}", screen.getBlockPos());
                        var pkt = new NextEpisodePacket(screen.getBlockPos());
                        mc.getConnection().send(new ServerboundCustomPayloadPacket(pkt));
                    }
                }
                // URL 变了 → 取消该屏在途解析（防止旧解析完成后复活已停止的旧播放器）、停旧播放器，下次 tick 自动启动新的
                if (!url.isEmpty() && !url.equals(sp.lastEpisodeUrl)) {
                    me.zuogeren.kazumiplayer.playback.source.VideoSourceResolver.getInstance()
                        .cancelResolve(screen.getBlockPos());
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
        // 音响方块被移除兜底：BE 不再渲染，tick 不会触发 → 按位置检查并清理音频
        for (var pos : SpeakerClientAudio.getActivePositions()) {
            var be = mc.level.getBlockEntity(pos);
            if (!(be instanceof me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity)
                    || be.isRemoved()) {
                SpeakerClientAudio.remove(pos);
            }
        }
    }
}
