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
