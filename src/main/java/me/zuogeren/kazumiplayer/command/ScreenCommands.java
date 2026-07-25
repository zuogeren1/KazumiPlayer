package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public class ScreenCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("screen")
            .then(Commands.literal("create")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .then(Commands.argument("width", FloatArgumentType.floatArg(0.5f, 10f))
                        .then(Commands.argument("height", FloatArgumentType.floatArg(0.5f, 10f))
                            .executes(ctx -> {
                                // TODO Phase 4: 完整屏幕创建逻辑
                                BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                float w = FloatArgumentType.getFloat(ctx, "width");
                                float h = FloatArgumentType.getFloat(ctx, "height");
                                ServerPlayer player = ctx.getSource().getPlayerOrException();

                                player.sendSystemMessage(Component.literal(
                                    "屏幕创建功能将在 Phase 4 实现。位置: " + pos.toShortString()
                                    + ", 尺寸: " + w + "x" + h));
                                return 1;
                            })))));
    }
}
