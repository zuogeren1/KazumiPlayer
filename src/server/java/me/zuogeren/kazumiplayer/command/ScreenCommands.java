package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlock;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public class ScreenCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("screen")
            .then(Commands.literal("create")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("width", FloatArgumentType.floatArg(0.5f, 128f))
                .then(Commands.argument("height", FloatArgumentType.floatArg(0.5f, 128f))
                .then(Commands.argument("facing", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        builder.suggest("north");
                        builder.suggest("south");
                        builder.suggest("east");
                        builder.suggest("west");
                        return builder.buildFuture();
                    })
                    .executes(ctx -> createScreen(ctx.getSource(),
                        BlockPosArgument.getBlockPos(ctx, "pos"),
                        FloatArgumentType.getFloat(ctx, "width"),
                        FloatArgumentType.getFloat(ctx, "height"),
                        StringArgumentType.getString(ctx, "facing"))))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    Direction facing = player.getDirection();
                    // 四舍五入到东西南北
                    facing = roundToCardinal(facing);
                    return createScreen(ctx.getSource(),
                        BlockPosArgument.getBlockPos(ctx, "pos"),
                        FloatArgumentType.getFloat(ctx, "width"),
                        FloatArgumentType.getFloat(ctx, "height"),
                        facing.getName());
                })))))
            .then(Commands.literal("stop")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    BlockPos pos = PlayCommands.getTargetScreen(player);
                    if (pos == null) {
                        ctx.getSource().sendFailure(KazumiMessages.error("请瞄准一个屏幕!"));
                        return 0;
                    }
                    var be = player.level().getBlockEntity(pos);
                    if (!(be instanceof VideoScreenBlockEntity screen)) {
                        ctx.getSource().sendFailure(KazumiMessages.error("请瞄准一个屏幕!"));
                        return 0;
                    }
                    UUID sid = screen.getScreenId();
                    // 停止前保存当前计时到 NBT
                    var g = SyncGroupManager.get().getGroup(sid);
                    if (g != null) {
                        long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
                        screen.updateSyncPosition(g.positionMs + elapsed);
                    }
                    screen.clearPlayback();
                    SyncGroupManager.get().leaveByScreenId(sid);
                    ctx.getSource().sendSuccess(() -> KazumiMessages.success("屏幕已停止"), true);
                    return 1;
                }));
    }

    private static int createScreen(CommandSourceStack src, BlockPos pos,
                                     float width, float height, String facingName) {
        ServerLevel level = src.getLevel();
        Direction facing = Direction.byName(facingName);
        if (facing == null) {
            src.sendFailure(KazumiMessages.error("无效方向: " + facingName + " (可用: north/south/east/west)"));
            return 0;
        }

        BlockState state = VideoScreenRegistration.VIDEO_SCREEN_BLOCK.get()
                .defaultBlockState()
                .setValue(VideoScreenBlock.FACING, facing);

        level.setBlock(pos, state, 3);

        if (level.getBlockEntity(pos) instanceof VideoScreenBlockEntity be) {
            be.setScreenSize(width, height);
            be.setFacing(facing);
            src.sendSuccess(() -> KazumiMessages.success(
                "屏幕已创建: " + pos.toShortString() + " (" + width + "x" + height + " 面向 " + facing + ")"), true);
        }
        return 1;
    }

    private static Direction roundToCardinal(Direction dir) {
        return switch (dir) {
            case NORTH, SOUTH, EAST, WEST -> dir;
            default -> {
                double yaw = Math.toRadians(dir.toYRot());
                double angle = Math.atan2(-Math.sin(yaw), Math.cos(yaw));
                int octant = (int) Math.round(4 * angle / Math.PI) & 7;
                yield switch (octant) {
                    case 0 -> Direction.SOUTH;
                    case 1, 2 -> Direction.WEST;
                    case 3, 4 -> Direction.NORTH;
                    case 5, 6 -> Direction.EAST;
                    default -> Direction.SOUTH;
                };
            }
        };
    }
}
