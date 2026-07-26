package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

public class PlayCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            RuleManager ruleManager, SearchManager searchManager) {

        // /kazumi play <rule> <resultId> <episode>
        var play = Commands.literal("play")
            .then(Commands.argument("rule", StringArgumentType.string())
                .then(Commands.argument("resultId", StringArgumentType.string())
                    .then(Commands.argument("episode", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String ruleName = StringArgumentType.getString(ctx, "rule");
                            String resultId = StringArgumentType.getString(ctx, "resultId");
                            int episode = IntegerArgumentType.getInteger(ctx, "episode");

                            BlockPos screenPos = getTargetScreen(player);
                            if (screenPos == null) {
                                ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                                return 0;
                            }

                            Rule rule = ruleManager.get(ruleName);
                            if (rule == null) {
                                ctx.getSource().sendFailure(Component.literal("规则不存在: " + ruleName));
                                return 0;
                            }

                            var entry = searchManager.getCache().lookup(resultId);
                            if (entry == null) {
                                ctx.getSource().sendFailure(Component.literal("搜索结果已过期，请重新搜索"));
                                return 0;
                            }

                            String source = entry.item().src();
                            ctx.getSource().sendSystemMessage(Component.literal("正在获取剧集列表..."));

                            ruleManager.getEngine().queryChapters(rule, source)
                                .thenAccept(result -> {
                                    if (result.roads().isEmpty()) {
                                        ctx.getSource().sendFailure(Component.literal("未找到剧集列表"));
                                        return;
                                    }
                                    Road road = result.roads().get(0);
                                    int idx = Math.max(0, Math.min(episode - 1, road.data().size() - 1));
                                    String epUrl = road.data().get(idx);
                                    String roadJson = JsonUtil.GSON.toJson(result.roads());

                                    player.level().getServer().execute(() -> {
                                        var be = player.level().getBlockEntity(screenPos);
                                        if (be instanceof VideoScreenBlockEntity screen) {
                                            SyncGroupManager.get().onPlayStart(player, screen.getScreenId(), screenPos, epUrl);
                                            setScreenFull(screen, epUrl, 0, episode, roadJson);
                                        }
                                    });
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                        "正在播放: " + road.identifier().get(idx) + " (第" + episode + "集)"));
                                })
                                .exceptionally(e -> {
                                    ctx.getSource().sendFailure(Component.literal(
                                        "获取剧集失败: " + e.getMessage()));
                                    return null;
                                });

                            return 1;
                        }))));

        // /kazumi play-url <url>
        var playUrl = Commands.literal("play-url")
            .then(Commands.argument("url", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String url = StringArgumentType.getString(ctx, "url");
                    BlockPos screenPos = getTargetScreen(player);
                    if (screenPos == null) {
                        ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                        return 0;
                    }
                    var be = player.level().getBlockEntity(screenPos);
                    if (be instanceof VideoScreenBlockEntity screen) {
                        SyncGroupManager.get().onPlayStart(player, screen.getScreenId(), screenPos, url);
                        setScreenNbt(screen, url, 0);
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("已开始播放: " + url));
                    return 1;
                }));

        // /kazumi join
        var join = Commands.literal("join")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                BlockPos screenPos = getTargetScreen(player);
                if (screenPos == null) {
                    ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                    return 0;
                }
                var be = player.level().getBlockEntity(screenPos);
                if (!(be instanceof VideoScreenBlockEntity screen) || screen.getEpisodeUrl().isEmpty()) {
                    ctx.getSource().sendFailure(Component.literal("该屏幕未在播放"));
                    return 0;
                }
                String url = screen.getEpisodeUrl();
                UUID sid = screen.getScreenId();
                var group = SyncGroupManager.get().getGroup(sid);
                long currentPos;
                if (group != null) {
                    long elapsed = group.paused ? 0 : System.currentTimeMillis() - group.serverTimestamp;
                    currentPos = group.positionMs + elapsed;
                } else {
                    currentPos = screen.getSyncPositionMs();
                    if (currentPos < 0) currentPos = 0;
                    SyncGroupManager.get().onPlayStart(player, sid, screenPos, url);
                }
                SyncGroupManager.get().join(player, sid, url);
                setScreenNbt(screen, url, currentPos);
                notifyOtherWatchers(player, screenPos, sid, "加入了同步播放");
                ctx.getSource().sendSystemMessage(Component.literal(
                    "已加入同步播放 (位置: " + (currentPos / 1000) + "s)"));
                return 1;
            });

        // /kazumi play stop
        var playStop = Commands.literal("stop")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                BlockPos screenPos = getTargetScreen(player);
                if (screenPos == null) {
                    ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                    return 0;
                }
                // 离开前保存实时位置
                var be0 = player.level().getBlockEntity(screenPos);
                if (be0 instanceof VideoScreenBlockEntity sc) {
                    UUID sid = sc.getScreenId();
                    var g = SyncGroupManager.get().getGroup(sid);
                    if (g != null) {
                        long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
                        sc.updateSyncPosition(g.positionMs + elapsed);
                        notifyOtherWatchers(player, screenPos, sid, "停止了播放");
                    }
                }
                SyncGroupManager.get().leave(player.getUUID());
                PacketDistributor.sendToPlayer(player, new PlayStopPacket(screenPos));
                // 同步 WatchingPlayers（leave 后若组被删则清空）
                var be1 = player.level().getBlockEntity(screenPos);
                if (be1 instanceof VideoScreenBlockEntity sc) syncWatchingPlayersOrClear(sc);
                ctx.getSource().sendSystemMessage(Component.literal("已停止当前客户端播放"));
                return 1;
            });

        // /kazumi play leave = /kazumi play stop
        var playLeave = Commands.literal("leave")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                BlockPos screenPos = getTargetScreen(player);
                if (screenPos == null) {
                    ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                    return 0;
                }
                // 离开前保存实时位置
                var be0 = player.level().getBlockEntity(screenPos);
                if (be0 instanceof VideoScreenBlockEntity sc) {
                    UUID sid = sc.getScreenId();
                    var g = SyncGroupManager.get().getGroup(sid);
                    if (g != null) {
                        long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
                        sc.updateSyncPosition(g.positionMs + elapsed);
                        notifyOtherWatchers(player, screenPos, sid, "离开了同步播放");
                    }
                }
                SyncGroupManager.get().leave(player.getUUID());
                PacketDistributor.sendToPlayer(player, new PlayStopPacket(screenPos));
                var be1 = player.level().getBlockEntity(screenPos);
                if (be1 instanceof VideoScreenBlockEntity sc) syncWatchingPlayersOrClear(sc);
                ctx.getSource().sendSystemMessage(Component.literal("已离开同步播放"));
                return 1;
            });

        return play.then(playUrl).then(join).then(playStop).then(playLeave);
    }

    /** 顶层命令：next/prev/time/episodes/pause/resume，单独注册到 /kazumi 下 */
    public static void registerTopLevel(CommandDispatcher<CommandSourceStack> dispatcher,
                                         RuleManager ruleManager, SearchManager searchManager) {
        // /kazumi pause - 暂停
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("pause")
                .executes(ctx -> togglePause(ctx.getSource(), true))));

        // /kazumi resume - 恢复
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("resume")
                .executes(ctx -> togglePause(ctx.getSource(), false))));

        // /kazumi next
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("next")
                .executes(ctx -> switchEpisode(ctx.getSource(), true))));

        // /kazumi prev
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("prev")
                .executes(ctx -> switchEpisode(ctx.getSource(), false))));

        // /kazumi time forward/back/goto
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("time")
                .then(Commands.literal("forward")
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                        .executes(ctx -> adjustTime(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("back")
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                        .executes(ctx -> adjustTime(ctx.getSource(),
                            -IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("goto")
                    .then(Commands.argument("time", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            long ms = parseTime(StringArgumentType.getString(ctx, "time"));
                            if (ms < 0) {
                                ctx.getSource().sendFailure(Component.literal("格式错误, 例: 2:30 或 1:05:00"));
                                return 0;
                            }
                            return adjustTimeAbs(ctx.getSource(), ms);
                        })))));

        // /kazumi episodes <rule> <resultId> [page]
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("episodes")
                .then(Commands.argument("rule", StringArgumentType.string())
                    .then(Commands.argument("resultId", StringArgumentType.string())
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> showEpisodes(ctx.getSource(),
                                StringArgumentType.getString(ctx, "rule"),
                                StringArgumentType.getString(ctx, "resultId"),
                                IntegerArgumentType.getInteger(ctx, "page"),
                                ruleManager, searchManager)))
                        .executes(ctx -> showEpisodes(ctx.getSource(),
                            StringArgumentType.getString(ctx, "rule"),
                            StringArgumentType.getString(ctx, "resultId"), 1,
                            ruleManager, searchManager))))));
    }

    // ---- 集数展示 ----

    private static int showEpisodes(CommandSourceStack src, String ruleName, String resultId,
                                     int page, RuleManager ruleManager, SearchManager searchManager) {
        Rule rule = ruleManager.get(ruleName);
        if (rule == null) { src.sendFailure(Component.literal("规则不存在: " + ruleName)); return 0; }
        var entry = searchManager.getCache().lookup(resultId);
        if (entry == null) { src.sendFailure(Component.literal("搜索结果已过期")); return 0; }

        src.sendSystemMessage(Component.literal("正在获取集数列表..."));
        ruleManager.getEngine().queryChapters(rule, entry.item().src())
            .thenAccept(result -> {
                if (result.roads().isEmpty()) { src.sendFailure(Component.literal("未找到集数")); return; }
                Road road = result.roads().get(0);
                int total = road.data().size();
                int perPage = 36; // 每页36集 (6列×6行)
                int totalPages = (total + perPage - 1) / perPage;
                int cp = Math.max(1, Math.min(page, totalPages));
                int start = (cp - 1) * perPage;
                int end = Math.min(start + perPage, total);
                String roadJson = JsonUtil.GSON.toJson(result.roads());

                src.sendSystemMessage(Component.literal("=== " + entry.item().name()
                    + " 共" + total + "集 (第" + cp + "/" + totalPages + "页) ==="));

                // 每行 6 个集数
                var line = Component.literal("");
                int count = 0;
                for (int i = start; i < end; i++) {
                    String label = road.identifier().size() > i ? road.identifier().get(i) : ("第" + (i+1) + "集");
                    // 生成播放命令，含完整 Road JSON
                    String cmd = "/kazumi play " + ruleName + " " + resultId + " " + (i + 1);
                    line.append(ChatComponentUtil.clickable("[" + label + "] ", cmd, "点击播放 " + label));
                    count++;
                    if (count % 6 == 0) {
                        src.sendSystemMessage(line);
                        line = Component.literal("");
                    }
                }
                if (count % 6 != 0) src.sendSystemMessage(line);

                // 翻页
                if (totalPages > 1) {
                    var nav = Component.literal("").withStyle(net.minecraft.ChatFormatting.GRAY);
                    if (cp > 1) {
                        int prev = cp - 1;
                        nav.append(ChatComponentUtil.clickable("<<< 上一页  ",
                            "/kazumi episodes " + ruleName + " " + resultId + " " + prev, "第" + prev + "页"));
                    }
                    if (cp < totalPages) {
                        int next = cp + 1;
                        nav.append(ChatComponentUtil.clickable(">>> 下一页",
                            "/kazumi episodes " + ruleName + " " + resultId + " " + next, "第" + next + "页"));
                    }
                    src.sendSystemMessage(nav);
                }
            })
            .exceptionally(e -> { src.sendFailure(Component.literal("获取失败: " + e.getMessage())); return null; });
        return 1;
    }

    // ---- 切集 ----

    private static int switchEpisode(CommandSourceStack src, boolean next) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        BlockPos screenPos = getTargetScreen(player);
        if (screenPos == null) {
            src.sendFailure(Component.literal("请瞄准一个屏幕!"));
            return 0;
        }
        var be = player.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen) || screen.getEpisodeData().isEmpty()) {
            src.sendFailure(Component.literal("该屏幕无可切换的集数"));
            return 0;
        }
        List<Road> roads = JsonUtil.GSON.fromJson(screen.getEpisodeData(),
            new com.google.gson.reflect.TypeToken<List<Road>>() {}.getType());
        if (roads == null || roads.isEmpty()) return 0;
        Road road = roads.get(0);
        int idx = screen.getEpisodeIndex();
        idx = next ? idx + 1 : idx - 1;
        if (idx < 1 || idx > road.data().size()) {
            src.sendFailure(Component.literal(next ? "已是最后一集" : "已是第一集"));
            return 0;
        }
        String url = road.data().get(idx - 1);
        String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
        String roadJson = JsonUtil.GSON.toJson(roads);
        screen.setPlaybackFull(url, 0, idx, roadJson);
        UUID sid = screen.getScreenId();
        SyncGroupManager.get().onPlayStart(player, sid, screenPos, url);
        syncWatchingPlayers(screen);
        notifyOtherWatchers(player, screenPos, sid, "切换到 " + name);
        src.sendSystemMessage(Component.literal("已切换到: " + name));
        return 1;
    }

    // ---- 时间调整 ----

    private static int adjustTime(CommandSourceStack src, int deltaSec) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        BlockPos screenPos = getTargetScreen(player);
        if (screenPos == null) { src.sendFailure(Component.literal("请瞄准一个屏幕!")); return 0; }
        var be = player.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) { src.sendFailure(Component.literal("目标方块不是屏幕")); return 0; }
        long cur = getLivePosition(screen);
        long newPos = Math.max(0, cur + deltaSec * 1000L);
        screen.updateSyncPosition(newPos);
        UUID sid = screen.getScreenId();
        updateSyncGroupPosition(sid, newPos);
        String action = deltaSec >= 0
            ? "快进了 " + deltaSec + "s → " + formatMs(newPos)
            : "快退了 " + (-deltaSec) + "s → " + formatMs(newPos);
        notifyOtherWatchers(player, screenPos, sid, action);
        src.sendSystemMessage(Component.literal("时间调整: " + (deltaSec >= 0 ? "+" : "") + deltaSec + "s → " + formatMs(newPos)));
        return 1;
    }

    private static int adjustTimeAbs(CommandSourceStack src, long ms) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        BlockPos screenPos = getTargetScreen(player);
        if (screenPos == null) { src.sendFailure(Component.literal("请瞄准一个屏幕!")); return 0; }
        var be = player.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) { src.sendFailure(Component.literal("目标方块不是屏幕")); return 0; }
        screen.updateSyncPosition(ms);
        UUID sid = screen.getScreenId();
        updateSyncGroupPosition(sid, ms);
        notifyOtherWatchers(player, screenPos, sid, "跳转到 " + formatMs(ms));
        src.sendSystemMessage(Component.literal("跳转到: " + formatMs(ms)));
        return 1;
    }

    private static long parseTime(String t) {
        try {
            String[] parts = t.split(":");
            if (parts.length == 2) { // M:SS
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            } else if (parts.length == 3) { // H:MM:SS
                return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    /** 从 SyncGroup 读实时位置，fallback 到 NBT */
    private static long getLivePosition(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        if (g != null) {
            long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
            return g.positionMs + elapsed;
        }
        long nbt = screen.getSyncPositionMs();
        return nbt >= 0 ? nbt : 0;
    }

    private static void updateSyncGroupPosition(UUID screenId, long newPos) {
        var g = SyncGroupManager.get().getGroup(screenId);
        if (g != null) {
            SyncGroupManager.get().updateState(screenId, newPos, g.paused);
        }
    }

    private static int togglePause(CommandSourceStack src, boolean pause) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        BlockPos pos = getTargetScreen(player);
        if (pos == null) { src.sendFailure(Component.literal("请瞄准一个屏幕!")); return 0; }
        var be = player.level().getBlockEntity(pos);
        if (!(be instanceof VideoScreenBlockEntity screen)) { src.sendFailure(Component.literal("目标方块不是屏幕")); return 0; }
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g == null) { src.sendFailure(Component.literal("该屏幕未在播放")); return 0; }
        long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
        long cur = g.positionMs + elapsed;
        SyncGroupManager.get().updateState(sid, cur, pause);
        screen.updateSyncPosition(cur);
        screen.setPlaybackPaused(pause);
        notifyOtherWatchers(player, pos, sid, pause ? "暂停了播放" : "恢复了播放");
        src.sendSystemMessage(Component.literal(pause ? "已暂停" : "已恢复"));
        return 1;
    }

    private static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    // ---- 辅助 ----

    private static void setScreenNbt(VideoScreenBlockEntity screen, String url, long positionMs) {
        screen.setPlayback(url, positionMs);
        syncWatchingPlayers(screen);
    }

    private static void setScreenFull(VideoScreenBlockEntity screen, String url,
                                       long positionMs, int episodeIdx, String roadJson) {
        screen.setPlaybackFull(url, positionMs, episodeIdx, roadJson);
        syncWatchingPlayers(screen);
    }

    private static void syncWatchingPlayers(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        if (g != null) {
            String list = String.join(",", g.players.stream().map(java.util.UUID::toString).toList());
            screen.setWatchingPlayers(list);
        }
    }

    /** 同步 WatchingPlayers；组被删（最后一人离开）时清空 */
    private static void syncWatchingPlayersOrClear(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        screen.setWatchingPlayers(g != null
            ? String.join(",", g.players.stream().map(java.util.UUID::toString).toList())
            : "");
    }

    /** 通知其他观看者（不包括操作者本人） */
    private static void notifyOtherWatchers(ServerPlayer actor, BlockPos pos, UUID screenId, String action) {
        var g = SyncGroupManager.get().getGroup(screenId);
        if (g == null) return;
        String actorName = actor.getName().getString();
        Component msg = Component.literal("§e" + actorName + " " + action + " §7("
            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
        var server = ((net.minecraft.server.level.ServerLevel) actor.level()).getServer();
        for (UUID pid : g.players) {
            if (pid.equals(actor.getUUID())) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    private static BlockPos getTargetScreen(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0, false);
        if (hit instanceof BlockHitResult blockHit) return blockHit.getBlockPos();
        return null;
    }
}
