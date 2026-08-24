package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.PlaybackController;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * 播放相关命令（纯参数解析层）：业务一律委托 {@link PlaybackController}。
 *
 * <p>命令树：
 * <pre>
 * /kazumi play &lt;规则&gt; &lt;ID&gt; &lt;集数&gt; [线路]   按规则播放
 * /kazumi play url &lt;url&gt;                  直链播放/排队
 * /kazumi play join | stop | leave         加入/停止/离开同步
 * /kazumi control next | prev | pause | resume
 * /kazumi control time forward|back|goto
 * </pre>
 * 兼容别名（透明转发）：/kazumi play-url、/kazumi join。
 */
public class PlayCommands {

    /** 单次命令的目标三元组（校验通过后才有值） */
    private record Target(ServerPlayer player, BlockPos pos, VideoScreenBlockEntity screen) {}

    /** 解析并校验玩家所瞄准的屏幕；失败时已向来源发送提示并返回 null */
    private static Target target(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        BlockPos pos = getTargetScreen(player);
        if (pos == null) {
            src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.aim")));
            return null;
        }
        if (!(player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)) {
            src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.err.not_screen")));
            return null;
        }
        return new Target(player, pos, screen);
    }

    @FunctionalInterface
    private interface ControlOp {
        PlaybackController.OpResult run(ServerPlayer player, BlockPos pos, VideoScreenBlockEntity screen);
    }

    /** 控制类命令通用执行：委托 Controller → 按结果回显 */
    private static int execute(CommandSourceStack src, String successPrefix, ControlOp op) {
        try {
            Target t = target(src);
            if (t == null) return 0;
            var r = op.run(t.player(), t.pos(), t.screen());
            if (r.success()) src.sendSuccess(() -> KazumiMessages.successKey("kazumiplayer.msg.ok.generic",
                    r.detail().getString()), false);
            else src.sendFailure(KazumiMessages.errorOf(r.detail()));
            return r.success() ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
    }

    // ---- /kazumi play 子树 ----

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            RuleManager ruleManager, SearchManager searchManager) {

        // /kazumi play <rule> <resultId> <episode> [road]  road 为 1-based 线路号，缺省 1
        var play = Commands.literal("play")
            .then(Commands.argument("rule", StringArgumentType.string())
                .then(Commands.argument("resultId", StringArgumentType.string())
                    .then(Commands.argument("episode", IntegerArgumentType.integer(1))
                        .executes(ctx -> executePlay(ctx, ruleManager, searchManager, 1))
                        .then(Commands.argument("road", IntegerArgumentType.integer(1))
                            .executes(ctx -> executePlay(ctx, ruleManager, searchManager,
                                IntegerArgumentType.getInteger(ctx, "road")))))));

        // /kazumi play url <url> —— 与 GUI 直链队列同语义：空闲起播 / 队列播放中追加 / 剧集播放中拒绝
        var playUrl = Commands.literal("url")
            .then(Commands.argument("url", StringArgumentType.greedyString())
                .executes(ctx -> submitQueueUrl(ctx)));

        // /kazumi play join
        var join = Commands.literal("join")
            .executes(ctx -> execute(ctx.getSource(), "", (p, pos, s) -> PlaybackController.joinScreen(p, pos, s)));

        // /kazumi play stop
        var stop = Commands.literal("stop")
            .executes(ctx -> execute(ctx.getSource(), "",
                (p, pos, s) -> { PlaybackController.leaveOwn(p, pos, s, Component.translatable("kazumiplayer.msg.notify.stop_playing").getString());
                    return PlaybackController.OpResult.ok(Component.translatable("kazumiplayer.msg.ok.stopped_client")); }));

        // /kazumi play leave
        var leave = Commands.literal("leave")
            .executes(ctx -> execute(ctx.getSource(), "",
                (p, pos, s) -> { PlaybackController.leaveOwn(p, pos, s, Component.translatable("kazumiplayer.msg.notify.left_sync").getString());
                    return PlaybackController.OpResult.ok(Component.translatable("kazumiplayer.msg.ok.left")); }));

        return play.then(playUrl).then(join).then(stop).then(leave);
    }

    // ---- /kazumi control 子树 ----

    public static LiteralArgumentBuilder<CommandSourceStack> buildControl() {
        return Commands.literal("control")
            .then(Commands.literal("next")
                .executes(ctx -> execute(ctx.getSource(), Component.translatable("kazumiplayer.cmd.play.switched_to").getString(), (p, pos, s) -> PlaybackController.switchEpisode(p, pos, s, true))))
            .then(Commands.literal("prev")
                .executes(ctx -> execute(ctx.getSource(), Component.translatable("kazumiplayer.cmd.play.switched_to").getString(), (p, pos, s) -> PlaybackController.switchEpisode(p, pos, s, false))))
            .then(Commands.literal("pause")
                .executes(ctx -> execute(ctx.getSource(), "", (p, pos, s) -> PlaybackController.setPaused(p, pos, s, true))))
            .then(Commands.literal("resume")
                .executes(ctx -> execute(ctx.getSource(), "", (p, pos, s) -> PlaybackController.setPaused(p, pos, s, false))))
            .then(Commands.literal("time")
                .then(Commands.literal("forward")
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                        .executes(ctx -> execute(ctx.getSource(), Component.translatable("kazumiplayer.cmd.play.time_adjusted").getString(),
                            (p, pos, s) -> PlaybackController.adjustTime(p, pos, s,
                                IntegerArgumentType.getInteger(ctx, "seconds"))))))
                .then(Commands.literal("back")
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                        .executes(ctx -> execute(ctx.getSource(), Component.translatable("kazumiplayer.cmd.play.time_adjusted").getString(),
                            (p, pos, s) -> PlaybackController.adjustTime(p, pos, s,
                                -IntegerArgumentType.getInteger(ctx, "seconds"))))))
                .then(Commands.literal("goto")
                    .then(Commands.argument("time", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            long ms = parseTime(StringArgumentType.getString(ctx, "time"));
                            if (ms < 0) {
                                ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.play.bad_time_format")));
                                return 0;
                            }
                            return execute(ctx.getSource(), Component.translatable("kazumiplayer.cmd.play.goto").getString(),
                                (p, pos, s) -> PlaybackController.seekTo(p, pos, s, ms));
                        }))));
    }

    /** 旧一级写法的透明转发别名（文档已改推新路径，保留一个版本周期） */
    public static void registerCompatAliases(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("play-url")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                    .executes(ctx -> submitQueueUrl(ctx)))));
        dispatcher.register(Commands.literal("kazumi")
            .then(Commands.literal("join").executes(ctx ->
                execute(ctx.getSource(), "", (p, pos, s) -> PlaybackController.joinScreen(p, pos, s)))));
    }

    private static int submitQueueUrl(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String url = StringArgumentType.getString(ctx, "url");
            BlockPos screenPos = getTargetScreen(player);
            if (screenPos == null) {
                ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.aim")));
                return 0;
            }
            var err = me.zuogeren.kazumiplayer.network.QueueRequestHandlers.submit(
                player, screenPos, List.of(url));
            if (err != null) {
                ctx.getSource().sendFailure(KazumiMessages.errorOf(err.toComponent()));
                return 0;
            }
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
    }

    /**
     * /kazumi episodes <rule> <resultId> [page] —— 集数列表（一级查询域）
     */
    public static LiteralArgumentBuilder<CommandSourceStack> buildEpisodes(
            RuleManager ruleManager, SearchManager searchManager) {
        return Commands.literal("episodes")
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
                        ruleManager, searchManager))));
    }

    /**
     * /kazumi play 执行体。road 为 1-based 线路号（对齐 Kazumi 切线语义：
     * 保持集数序号，取目标线路的同序号集），越界钳制到有效线路范围。
     */
    private static int executePlay(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            RuleManager ruleManager, SearchManager searchManager, int roadNumber) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.player_only")));
            return 0;
        }
        String ruleName = StringArgumentType.getString(ctx, "rule");
        String resultId = StringArgumentType.getString(ctx, "resultId");
        int episode = IntegerArgumentType.getInteger(ctx, "episode");

        BlockPos screenPos = getTargetScreen(player);
        if (screenPos == null) {
            ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.aim")));
            return 0;
        }

        Rule rule = ruleManager.get(ruleName);
        if (rule == null) {
            ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule_not_found", ruleName)));
            return 0;
        }
        if (rule.isDeprecated()) {
            KazumiMessages.sendWarn(ctx.getSource(),
                Component.translatable("kazumiplayer.cmd.rule_deprecated_warn", ruleName).getString());
        }

        var entry = searchManager.getCache().lookup(resultId);
        if (entry == null) {
            ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.results_expired")));
            return 0;
        }

        String source = entry.item().src();
        KazumiMessages.sendInfoKey(ctx.getSource(), "kazumiplayer.cmd.episodes.fetching");

        ruleManager.getEngine().queryChapters(rule, source)
            .thenAccept(result -> {
                if (result.roads().isEmpty()) {
                    ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.episodes.not_found_list")));
                    return;
                }
                int roadIdx = Math.max(0, Math.min(roadNumber - 1, result.roads().size() - 1));
                Road road = result.roads().get(roadIdx);
                int idx = Math.max(0, Math.min(episode - 1, road.data().size() - 1));
                String epUrl = road.data().get(idx);
                String roadJson = JsonUtil.GSON.toJson(result.roads());

                player.level().getServer().execute(() -> {
                    var be = player.level().getBlockEntity(screenPos);
                    if (be instanceof VideoScreenBlockEntity screen) {
                        SyncGroupManager.get().onPlayStart(player, screen.getScreenId(), screenPos, epUrl);
                        screen.setPlaybackFull(epUrl, 0, roadIdx, episode, roadJson);
                        syncWatchingPlayers(screen);
                        screen.setPlayingTitle(entry.item().name());
                    }
                });
                String epName = road.identifier().size() > idx ? road.identifier().get(idx) : Component.translatable("kazumiplayer.gui.main.episode_n", episode).getString();
                KazumiMessages.sendSuccess(ctx.getSource(),
                    Component.translatable("kazumiplayer.cmd.play.now_playing_road", epName, episode, road.name()).getString());
            })
            .exceptionally(e -> {
                ctx.getSource().sendFailure(KazumiMessages.error(
                    "获取剧集失败: " + e.getMessage()));
                return null;
            });

        return 1;
    }

    // ---- 集数展示（查询域，无状态变更） ----

    private static int showEpisodes(CommandSourceStack src, String ruleName, String resultId,
                                     int page, RuleManager ruleManager, SearchManager searchManager) {
        Rule rule = ruleManager.get(ruleName);
        if (rule == null) { src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule_not_found", ruleName))); return 0; }
        var entry = searchManager.getCache().lookup(resultId);
        if (entry == null) { src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.results_expired_short"))); return 0; }

        KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.episodes.fetching");
        ruleManager.getEngine().queryChapters(rule, entry.item().src())
            .thenAccept(result -> {
                if (result.roads().isEmpty()) { src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.episodes.not_found"))); return; }
                Road road = result.roads().get(0);
                int total = road.data().size();
                int perPage = 36; // 每页36集 (6列×6行)
                int totalPages = (total + perPage - 1) / perPage;
                int cp = Math.max(1, Math.min(page, totalPages));
                int start = (cp - 1) * perPage;
                int end = Math.min(start + perPage, total);

                src.sendSystemMessage(KazumiMessages.separator());
                src.sendSystemMessage(Component.literal("=== " + entry.item().name()
                    + Component.translatable("kazumiplayer.cmd.episodes.header", total, cp, totalPages).getString() + " ==="));;

                var line = Component.literal("");
                int count = 0;
                for (int i = start; i < end; i++) {
                    String label = road.identifier().size() > i ? road.identifier().get(i) : Component.translatable("kazumiplayer.gui.main.episode_n", i+1).getString();
                    String cmd = "/kazumi play " + ruleName + " " + resultId + " " + (i + 1);
                    line.append(ChatComponentUtil.clickable("[" + label + "] ", cmd, Component.translatable("kazumiplayer.cmd.play.click_play", label).getString()));
                    count++;
                    if (count % 6 == 0) {
                        src.sendSystemMessage(line);
                        line = Component.literal("");
                    }
                }
                if (count % 6 != 0) src.sendSystemMessage(line);

                if (totalPages > 1) {
                    var nav = Component.literal("").withStyle(net.minecraft.ChatFormatting.GRAY);
                    if (cp > 1) {
                        int prev = cp - 1;
                        nav.append(ChatComponentUtil.clickable("<<< 上一页  ",
                            "/kazumi episodes " + ruleName + " " + resultId + " " + prev, Component.translatable("kazumiplayer.cmd.page.n", prev).getString()));
                    }
                    if (cp < totalPages) {
                        int next = cp + 1;
                        nav.append(ChatComponentUtil.clickable(">>> 下一页",
                            "/kazumi episodes " + ruleName + " " + resultId + " " + next, Component.translatable("kazumiplayer.cmd.page.n", next).getString()));
                    }
                    src.sendSystemMessage(nav);
                }
            })
            .exceptionally(e -> { KazumiMessages.sendErrorKey(src, "kazumiplayer.cmd.episodes.failed", String.valueOf(e.getMessage())); return null; });
        return 1;
    }

    // ---- 辅助 ----

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

    private static void syncWatchingPlayers(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        if (g != null) {
            screen.setWatchingPlayers(g.watchingPlayersString());
        }
    }

    /** 瞄准屏幕判定（包内共享：SearchCommands/ScreenCommands 亦复用） */
    static BlockPos getTargetScreen(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0, false);
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            if (player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity) return pos;
        }
        return null;
    }
}
