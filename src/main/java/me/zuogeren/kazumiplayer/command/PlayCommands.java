package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.network.packet.PlayStartPacket;
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

                    // 通过数据包通知客户端播放
                    PacketDistributor.sendToPlayer(player, new PlayStartPacket(
                        screenPos, url, "direct", System.currentTimeMillis()));
                    ctx.getSource().sendSystemMessage(Component.literal("已发送播放请求: " + url));
                    return 1;
                }));

        var join = Commands.literal("join")
            .executes(ctx -> {
                ctx.getSource().sendSystemMessage(Component.literal("同步播放功能将在 Phase 6 实现"));
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
