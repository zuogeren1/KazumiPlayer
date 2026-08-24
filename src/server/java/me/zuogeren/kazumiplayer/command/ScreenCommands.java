package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlock;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
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
                        ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.aim")));
                        return 0;
                    }
                    var be = player.level().getBlockEntity(pos);
                    if (!(be instanceof VideoScreenBlockEntity screen)) {
                        ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.aim")));
                        return 0;
                    }
                    UUID sid = screen.getScreenId();
                    // 停止前保存当前计时到 NBT
                    var g = SyncGroupManager.get().getGroup(sid);
                    if (g != null) {
                        screen.updateSyncPosition(g.livePositionMillis());
                    }
                    screen.clearPlayback();
                    SyncGroupManager.get().leaveByScreenId(sid);
                    ctx.getSource().sendSuccess(() -> KazumiMessages.successKey("kazumiplayer.cmd.screen.stopped"), true);
                    return 1;
                }))
            .then(Commands.literal("viewers")
                .executes(ctx -> showViewers(ctx.getSource())));
    }

    // ---- /kazumi screen viewers：观看玩家列表（查询域，无状态变更） ----

    /**
     * 列出目标屏幕的观看玩家：优先同步组实时成员，无组时回退解析 BE
     * WatchingPlayers NBT（待机组也写入该字段）。在线玩家显名，
     * 离线条目以短 UUID 标识。增删操作待权限系统落地后另行立项。
     */
    private static int showViewers(CommandSourceStack src) {
        try {
            ServerPlayer player = src.getPlayerOrException();
            BlockPos pos = PlayCommands.getTargetScreen(player);
            if (pos == null) {
                src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.aim")));
                return 0;
            }
            if (!(player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)) {
                src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.err.not_screen")));
                return 0;
            }

            List<UUID> watchers = resolveWatchers(screen);
            if (watchers.isEmpty()) {
                src.sendSuccess(() -> KazumiMessages.infoKey("kazumiplayer.cmd.screen.viewers.none"), false);
                return 1;
            }

            src.sendSystemMessage(KazumiMessages.separator());
            src.sendSystemMessage(Component.translatable("kazumiplayer.cmd.screen.viewers.header",
                pos.toShortString(), watchers.size()));
            for (UUID id : watchers) {
                ServerPlayer p = player.level().getServer().getPlayerList().getPlayer(id);
                if (p != null) {
                    src.sendSystemMessage(Component.translatable("kazumiplayer.cmd.screen.viewers.entry_online",
                        p.getName()).withStyle(ChatFormatting.GREEN));
                } else {
                    src.sendSystemMessage(Component.translatable("kazumiplayer.cmd.screen.viewers.entry_offline",
                        id.toString().substring(0, 8)).withStyle(ChatFormatting.GRAY));
                }
            }
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
    }

    /** 观看者来源：同步组内存实时态优先，空/无组时解析 BE NBT 的逗号分隔 UUID 列表 */
    private static List<UUID> resolveWatchers(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        if (g != null && !g.players.isEmpty()) return List.copyOf(g.players);

        List<UUID> out = new ArrayList<>();
        for (String s : screen.getWatchingPlayers().split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(UUID.fromString(t));
            } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    private static int createScreen(CommandSourceStack src, BlockPos pos,
                                     float width, float height, String facingName) {
        ServerLevel level = src.getLevel();
        Direction facing = Direction.byName(facingName);
        if (facing == null) {
            src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.screen.invalid_facing", facingName)));
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
                Component.translatable("kazumiplayer.cmd.screen.created", pos.toShortString(), String.valueOf(width), String.valueOf(height), facing.getName()).getString()), true);
        }
        return 1;
    }

    /**
     * 四舍五入到最近的水平基数方向。
     * player.getDirection() 恒返回水平四向之一（首分支恒等返回）；
     * default 分支为防御性兜底——历史实现的八分象限映射曾将东西写反
     * （WEST→EAST/EAST→WEST，南北因轴对称侥幸正确），现按 MC yaw 角度语义纠正：
     * 南 0° / 西 90° / 北 180° / 东 270°。
     */
    private static Direction roundToCardinal(Direction dir) {
        return switch (dir) {
            case NORTH, SOUTH, EAST, WEST -> dir;
            default -> {
                float yrot = ((dir.toYRot() % 360f) + 360f) % 360f;
                yield (yrot >= 45f && yrot < 135f) ? Direction.WEST
                    : (yrot >= 135f && yrot < 225f) ? Direction.NORTH
                    : (yrot >= 225f && yrot < 315f) ? Direction.EAST
                    : Direction.SOUTH;
            }
        };
    }
}
