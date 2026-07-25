package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.network.packet.PlayStartPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
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

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
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
                            // Phase 5: 发送播放请求到客户端
                            ctx.getSource().sendSystemMessage(Component.literal(
                                "正在准备播放... (Phase 5: " + ruleName + " ep" + episode + ")"));
                            return 1;
                        }))));

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

                    // 创建同步组 + 通知客户端播放
                    SyncGroupManager.get().onPlayStart(player, screenPos, url);
                    PacketDistributor.sendToPlayer(player, new PlayStartPacket(
                        screenPos, url, "direct", System.currentTimeMillis()));
                    ctx.getSource().sendSystemMessage(Component.literal("已开始播放: " + url));
                    return 1;
                }));

        var join = Commands.literal("join")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                BlockPos screenPos = getTargetScreen(player);
                if (screenPos == null) {
                    ctx.getSource().sendFailure(Component.literal("请瞄准一个屏幕!"));
                    return 0;
                }
                SyncGroupManager mgr = SyncGroupManager.get();
                SyncGroupManager.SyncGroup group = mgr.getGroup(screenPos);
                if (group == null) {
                    ctx.getSource().sendFailure(Component.literal("该屏幕未在播放"));
                    return 0;
                }
                mgr.join(player, screenPos, group.videoUrl);
                // 发送当前同步状态给新加入的玩家
                PacketDistributor.sendToPlayer(player, new SyncStatePacket(
                    screenPos, group.videoUrl, group.positionMs, group.paused, group.serverTimestamp));
                ctx.getSource().sendSystemMessage(Component.literal("已加入同步播放"));
                return 1;
            });

        return play.then(playUrl).then(join);
    }

    private static BlockPos getTargetScreen(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0, false);
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }
}
