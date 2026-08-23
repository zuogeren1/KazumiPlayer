package me.zuogeren.kazumiplayer.client;
import me.zuogeren.kazumiplayer.util.KazumiMessages;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 客户端杂项事件处理器：GLFW 光标防御 + /krule 规则测试命令。
 * 播放调度与生命周期清理已拆至 {@link ClientPlaybackScheduler}。
 */
public class ClientDisconnectHandler {
    // ---- 调度节奏常量 ----
    private static final int MOUSE_STUCK_DEBOUNCE_TICKS = 2;        // 光标重捕防抖（0.1s）

    private static int mouseStuckTicks;

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
        // 全屏观影模式刻意释放光标（画面上有可点击的退出按钮），不参与此防御
        if (windowActive && mc.screen == null && !ClientFullscreenState.isActive()) {
            int realCursorMode = org.lwjgl.glfw.GLFW.glfwGetInputMode(
                    mc.getWindow().handle(), GLFW_CURSOR);
            if (realCursorMode != GLFW_CURSOR_DISABLED) {
                if (++mouseStuckTicks > MOUSE_STUCK_DEBOUNCE_TICKS) {
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
}
