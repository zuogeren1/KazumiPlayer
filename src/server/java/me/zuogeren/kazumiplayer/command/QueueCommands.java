package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.zuogeren.kazumiplayer.network.QueueRequestHandlers;
import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 直链队列命令（纯参数解析层）：业务一律委托 {@link QueueRequestHandlers}，
 * 与 GUI 的 queue_add/jump/move/remove 共用同一套服务端语义，禁止在此手搓组合。
 *
 * <p>命令树：
 * <pre>
 * /kazumi queue list [page]      查看队列（行可点击直接切播）
 * /kazumi queue add &lt;url&gt;        提交直链（空闲起播/队尾追加，与 /kazumi play url 同语义）
 * /kazumi queue jump &lt;index&gt;     切播任意项（已播项可重播）
 * /kazumi queue move &lt;index&gt;     插队到当前项之后
 * /kazumi queue remove &lt;index&gt;   移除队列项（当前项不可移除）
 * </pre>
 * 序号均为 1-based；越界/互斥等校验由服务端处理器统一完成并经 {@link GuiPayloads.ErrorPayload} 回显。
 */
public class QueueCommands {

    /** 列表每页行数 */
    private static final int PER_PAGE = 10;

    /** 单次命令的目标三元组（校验通过后才有值），与 PlayCommands.Target 同构 */
    private record Target(ServerPlayer player, BlockPos pos, VideoScreenBlockEntity screen) {}

    /** 解析并校验玩家所瞄准的屏幕；失败时已向来源发送提示并返回 null */
    private static Target target(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        BlockPos pos = PlayCommands.getTargetScreen(player);
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

    /** 统一收尾：处理器返回错误则回显失败，null 表示成功 */
    private static int finish(CommandSourceStack src, GuiPayloads.ErrorPayload err) {
        if (err != null) {
            src.sendFailure(KazumiMessages.errorOf(err.toComponent()));
            return 0;
        }
        return 1;
    }

    @FunctionalInterface
    private interface IndexOp {
        GuiPayloads.ErrorPayload run(ServerPlayer player, BlockPos pos, int index);
    }

    /** 带序号操作的通用执行体（jump/move/remove 共用） */
    private static int executeIndex(CommandSourceStack src, int index, IndexOp op) {
        try {
            Target t = target(src);
            if (t == null) return 0;
            return finish(src, op.run(t.player(), t.pos(), index));
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }

    // ---- /kazumi queue 子树 ----

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("queue")
            .then(Commands.literal("list")
                .executes(ctx -> showQueue(ctx.getSource(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> showQueue(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "page")))))
            .then(Commands.literal("add")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                    .executes(QueueCommands::submit)))
            .then(Commands.literal("jump")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> executeIndex(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "index"), QueueRequestHandlers::jump))))
            .then(Commands.literal("move")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> executeIndex(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "index"), QueueRequestHandlers::moveAfterCurrent))))
            .then(Commands.literal("remove")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> executeIndex(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "index"), QueueRequestHandlers::remove))));
    }

    private static int submit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Target t = target(ctx.getSource());
        if (t == null) return 0;
        return finish(ctx.getSource(), QueueRequestHandlers.submit(t.player(), t.pos(),
            List.of(StringArgumentType.getString(ctx, "url"))));
    }

    // ---- 队列展示（查询域，无状态变更） ----

    /**
     * 队列列表：读绑定屏幕 BE 经 markDirty 同步来的 EpisodeData（{@link DirectLinkQueue#parseUrls}），
     * 标注当前播放项与已播区；待播区行可点击执行 /kazumi queue jump N。
     */
    private static int showQueue(CommandSourceStack src, int page) {
        try {
            Target t = target(src);
            if (t == null) return 0;
            List<String> urls = DirectLinkQueue.parseUrls(t.screen().getEpisodeData());
            if (urls == null || urls.isEmpty()) {
                src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.err.no_queue")));
                return 0;
            }

            int total = urls.size();
            int totalPages = (total + PER_PAGE - 1) / PER_PAGE;
            int cp = Math.max(1, Math.min(page, totalPages));
            int start = (cp - 1) * PER_PAGE;
            int end = Math.min(start + PER_PAGE, total);
            int cur = Math.max(1, Math.min(t.screen().getEpisodeIndex(), total));

            src.sendSystemMessage(KazumiMessages.separator());
            src.sendSystemMessage(Component.translatable("kazumiplayer.cmd.queue.header", cp, totalPages, total));

            List<String> labels = DirectLinkQueue.parseLabels(t.screen().getEpisodeData());
            for (int i = start; i < end; i++) {
                src.sendSystemMessage(queueRow(urls.get(i), labels, i + 1, cur));
            }

            if (totalPages > 1) {
                var nav = Component.literal("").withStyle(ChatFormatting.GRAY);
                if (cp > 1) {
                    int prev = cp - 1;
                    nav.append(ChatComponentUtil.clickable(Component.translatable("kazumiplayer.cmd.nav.prev"),
                        "/kazumi queue list " + prev, Component.translatable("kazumiplayer.cmd.page.n", prev)));
                }
                if (cp < totalPages) {
                    int next = cp + 1;
                    nav.append(ChatComponentUtil.clickable(Component.translatable("kazumiplayer.cmd.nav.next"),
                        "/kazumi queue list " + next, Component.translatable("kazumiplayer.cmd.nav.goto_page", next)));
                }
                src.sendSystemMessage(nav);
            }
            return 1;
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }

    /** 队列行：当前项金色 ▶、已播区灰色、待播区绿色可点击切播 */
    private static MutableComponent queueRow(String url, List<String> labels, int index, int currentIndex) {
        String label = labels != null && index - 1 < labels.size() ? labels.get(index - 1) : null;
        if (label == null || label.isBlank()) label = DirectLinkQueue.makeLabel(url, index);
        if (index == currentIndex) {
            return Component.translatable("kazumiplayer.cmd.queue.row_current", index, label)
                .withStyle(ChatFormatting.GOLD);
        }
        if (index < currentIndex) {
            return Component.translatable("kazumiplayer.cmd.queue.row_played", index, label)
                .withStyle(ChatFormatting.GRAY);
        }
        return ChatComponentUtil.clickable(
            Component.translatable("kazumiplayer.cmd.queue.row_pending", index, label),
            "/kazumi queue jump " + index,
            Component.translatable("kazumiplayer.cmd.queue.click_jump", index));
    }
}
