package me.zuogeren.kazumiplayer.command;

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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

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

                                    // 切回服务端线程写 BE NBT
                                    player.level().getServer().execute(() -> {
                                        setScreenNbt(player, screenPos, epUrl, 0);
                                        SyncGroupManager.get().onPlayStart(player, screenPos, epUrl);
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

                    // 写入 BE NBT
                    setScreenNbt(player, screenPos, url, 0);

                    SyncGroupManager.get().onPlayStart(player, screenPos, url);
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

                // 先从 BE NBT 读 URL（持久化的真实来源）
                var be = player.level().getBlockEntity(screenPos);
                if (!(be instanceof VideoScreenBlockEntity screen) || screen.getEpisodeUrl().isEmpty()) {
                    ctx.getSource().sendFailure(Component.literal("该屏幕未在播放"));
                    return 0;
                }
                String url = screen.getEpisodeUrl();

                // SyncGroup 存在则用其进度，否则用 BE NBT 进度（从 0 开始）
                var group = SyncGroupManager.get().getGroup(screenPos);
                long currentPos;
                if (group != null) {
                    long elapsed = group.paused ? 0 : System.currentTimeMillis() - group.serverTimestamp;
                    currentPos = group.positionMs + elapsed;
                } else {
                    // 重建 SyncGroup
                    currentPos = screen.getSyncPositionMs();
                    if (currentPos < 0) currentPos = 0;
                    SyncGroupManager.get().onPlayStart(player, screenPos, url);
                }

                SyncGroupManager.get().join(player, screenPos, url);
                setScreenNbt(player, screenPos, url, currentPos);
                ctx.getSource().sendSystemMessage(Component.literal(
                    "已加入同步播放 (位置: " + (currentPos / 1000) + "s)"));
                return 1;
            });

        // /kazumi play stop - 仅停止当前客户端的播放
        var playStop = Commands.literal("stop")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                BlockPos screenPos = getTargetScreen(player);
                if (screenPos == null) {
                    ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                    return 0;
                }
                SyncGroupManager.get().leave(player.getUUID());
                PacketDistributor.sendToPlayer(player, new PlayStopPacket(screenPos));
                ctx.getSource().sendSystemMessage(Component.literal("已停止当前客户端播放"));
                return 1;
            });

        return play.then(playUrl).then(join).then(playStop);
    }

    /** 服务端：更新屏幕 BE 的 NBT，触发同步到客户端 */
    private static void setScreenNbt(ServerPlayer player, BlockPos screenPos, String url, long positionMs) {
        var be = player.level().getBlockEntity(screenPos);
        if (be instanceof VideoScreenBlockEntity screen) {
            screen.setPlayback(url, positionMs);
        } else {
            var logger = com.mojang.logging.LogUtils.getLogger();
            logger.warn("setScreenNbt: BE not found or wrong type at {} (found: {})", screenPos, be);
        }
    }

    private static BlockPos getTargetScreen(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0, false);
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }
}
