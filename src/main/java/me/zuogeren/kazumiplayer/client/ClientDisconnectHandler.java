package me.zuogeren.kazumiplayer.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.zuogeren.kazumiplayer.network.packet.PositionReportPacket;
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
import net.neoforged.neoforge.network.PacketDistributor;

public class ClientDisconnectHandler {

    private static int tickCounter;

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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof VideoScreenBlockEntity screen && screen.player != null) {
                screen.player.stop();
                screen.player = null;
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter % 20 != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof VideoScreenBlockEntity screen) {
                String url = screen.getEpisodeUrl();
                // 新播放：启动播放器并预置 seek
                if (screen.player == null && !url.isEmpty()) {
                    PlaybackManager pm = new PlaybackManager();
                    pm.playUrl(screen, url);
                    screen.player = pm.getWaterMedia();
                    long seekMs = screen.getSyncPositionMs();
                    if (seekMs > 0) screen.player.seek(seekMs);
                }
                // 已启动但 seek 未生效：等播放器就绪后重试
                if (screen.player != null && screen.player.hasPendingSeek() && screen.player.isPlaying()) {
                    screen.player.applyPendingSeek();
                }
            }
        }
    }
}
