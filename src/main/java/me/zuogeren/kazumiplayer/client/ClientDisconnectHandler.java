package me.zuogeren.kazumiplayer.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import org.slf4j.Logger;
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
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int tickCounter;
    private static final Set<VideoScreenBlockEntity> activeScreens = ConcurrentHashMap.newKeySet();

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
            if (screen.player != null) {
                screen.player.stop();
                screen.player = null;
            }
        }
        activeScreens.clear();
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
        for (var be : mc.level.getGloballyRenderedBlockEntities()) {
            if (be instanceof VideoScreenBlockEntity screen) {
                String url = screen.getEpisodeUrl();
                // 新播放：启动播放器并预置 seek
                // 只有 WatchingPlayers 中的玩家才自动播放（手动 join 后才能播）
                if (screen.player == null && !url.isEmpty() && isWatching(screen, mc)
                        && System.currentTimeMillis() - screen.playbackStartedAt > 3000) {
                    screen.playbackStartedAt = System.currentTimeMillis();
                    PlaybackManager pm = new PlaybackManager();
                    pm.playUrl(screen, url);
                    screen.player = pm.getWaterMedia();
                    activeScreens.add(screen);
                    screen.markSeen(url);
                    long seekMs = screen.getSyncPositionMs();
                    if (seekMs > 0) screen.player.seek(seekMs);
                }
                // 已启动但 seek 未生效：等播放器就绪后重试
                if (screen.player != null && screen.player.hasPendingSeek() && screen.player.isPlaying()) {
                    screen.player.applyPendingSeek();
                }
                // 暂停/恢复
                if (screen.player != null) {
                    if (screen.isPlaybackPaused()) {
                        screen.player.pause();
                    } else {
                        screen.player.resume();
                    }
                }
                // NBT 位置变化 → seek
                if (screen.player != null && screen.player.isPlaying()) {
                    long nbtPos = screen.getSyncPositionMs();
                    if (nbtPos >= 0 && nbtPos != screen.lastAppliedPosition) {
                        screen.lastAppliedPosition = nbtPos;
                        screen.player.seek(nbtPos);
                    }
                }
                // 检测播放完毕 → 自动下一集
                if (screen.player != null && screen.player.isEnded() && !screen.endedNotified) {
                    screen.endedNotified = true;
                    LOGGER.info("Auto-next: ended detected at screen {}", screen.getBlockPos());
                    var pkt = new NextEpisodePacket(screen.getBlockPos());
                    mc.getConnection().send(new ServerboundCustomPayloadPacket(pkt));
                }
                // URL 变了 → 停旧播放器，下次 tick 自动启动新的
                if (!url.isEmpty() && screen.justChanged(url)) {
                    if (screen.player != null) {
                        screen.player.stop();
                        screen.player = null;
                    }
                    screen.markSeen(url);
                    screen.endedNotified = false;
                }
            }
        }
    }
}
