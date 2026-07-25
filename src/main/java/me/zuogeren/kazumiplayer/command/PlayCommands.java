package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class PlayCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("play")
            .then(Commands.argument("rule", StringArgumentType.string())
                .then(Commands.argument("resultId", StringArgumentType.string())
                    .then(Commands.argument("episode", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            // TODO Phase 5: 完整播放逻辑
                            String rule = StringArgumentType.getString(ctx, "rule");
                            String resultId = StringArgumentType.getString(ctx, "resultId");
                            int episode = IntegerArgumentType.getInteger(ctx, "episode");

                            ctx.getSource().sendSystemMessage(Component.literal(
                                "播放功能将在 Phase 5 实现。规则: " + rule
                                + ", 结果ID: " + resultId + ", 集数: " + episode));
                            return 1;
                        }))))

            .then(Commands.literal("join")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal(
                        "同步播放功能将在 Phase 6 实现"));
                    return 1;
                }));
    }
}
