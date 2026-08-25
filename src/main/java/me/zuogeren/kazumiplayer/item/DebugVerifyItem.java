package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 审计/修复的游戏内验证工具（物品外壳永久保留）。
 *
 * 工作流：每次修复需要游戏内验证时，在 {@link #runSuite} 的【验证区】写入检查调用
 * （实现放【验证实现区】，复用下方输出辅助）。三层手段：
 * <ol>
 *   <li>模拟调用——反射调用服务端权威静态方法/读取私有字段做断言（编译期不破坏分层）</li>
 *   <li>输出直显——把修复后的聊天文案按真实组装路径渲染打印，肉眼确认措辞与颜色</li>
 *   <li>MANUAL——确实需要双端联机/实网/重启才能验的，写入报告供复制</li>
 * </ol>
 * 输出策略：PASS/MANUAL 只进缓冲不打聊天，聊天栏仅显示 FAIL（红）、区块标题、
 * 聊天样本与末尾汇总；末行附「复制完整报告」按钮（CopyToClipboard）一键取全量结果，
 * 避免长篇 DBG 内容污染聊天与日志。提交前把两个区域的内容清空，只保留外壳。
 *
 * <p>分层约束：本类在 common，禁止编译期引用 client/server source set 的类——
 * 跨层目标一律 {@code Class.forName} 字符串反射（运行时客户端 jar 同时含两端代码）。
 */
public class DebugVerifyItem extends Item {

    private static final StringBuilder BUF = new StringBuilder();
    private static int passCount;
    private static int failCount;
    private static int manualCount;

    public DebugVerifyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.SUCCESS;
        runSuite(player, null);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !ctx.getLevel().isClientSide()) return InteractionResult.PASS;
        runSuite(player, ctx.getClickedPos());
        return InteractionResult.SUCCESS;
    }

    private static void runSuite(Player player, BlockPos clickedPos) {
        BUF.setLength(0);
        passCount = failCount = manualCount = 0;

        line(player, "======== KazumiPlayer DBG ========");
        // ===== 验证区（提交前清空本区域调用，保留外壳）=====

        if (clickedPos != null) {
            appendScreenSnapshot(player, clickedPos);
        }

        // ===== 验证区结束 =====

        Component copyButton = Component.literal("[📋 复制完整报告]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.CopyToClipboard(BUF.toString()))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("点击复制全部 DBG 输出"))));
        player.sendSystemMessage(Component.literal(
                "[DBG] 共 " + (passCount + failCount + manualCount) + " 行：PASS " + passCount
                        + " / FAIL " + failCount + " / MANUAL " + manualCount + "  ")
                .withStyle(failCount > 0 ? ChatFormatting.RED : ChatFormatting.GREEN)
                .append(copyButton));
    }

    /** 右键屏幕方块时的附加快照：BE 权威 NBT 与本端播放器状态一览（场景核对用） */
    private static void appendScreenSnapshot(Player player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity be)) {
            gray("屏幕快照：目标不是视频屏幕方块 " + pos.toShortString());
            return;
        }
        buf("[DBG] ---- 屏幕快照 " + pos.toShortString() + " ----");
        buf("[DBG] 集/线路: " + be.getEpisodeIndex() + " (road " + be.getRoadIndex() + ")"
                + ", EpisodeData=" + (be.getEpisodeData().isEmpty() ? "无"
                : (DirectLinkQueue.isQueueData(be.getEpisodeData()) ? "队列" : "剧集"))
                + ", SyncPositionMs=" + be.getSyncPositionMs());
        String url = be.getEpisodeUrl();
        buf("[DBG] EpisodeUrl: " + (url.length() > 60 ? url.substring(0, 60) + "…" : url));
        int watchers = be.getWatchingPlayers().isEmpty()
                ? 0 : be.getWatchingPlayers().split(",").length;
        buf("[DBG] WatchingPlayers: " + watchers + " 人");

        // 本端播放状态经反射读取（ScreenPlayer 在 client 层）
        try {
            Class<?> spm = fqn("me.zuogeren.kazumiplayer.client.ScreenPlayerManager");
            Object sp = spm.getMethod("get", BlockPos.class).invoke(null, pos);
            Class<?> spCls = sp.getClass();
            boolean hasPlayer = spCls.getField("player").get(sp) != null;
            boolean everPlayed = spCls.getField("everPlayed").getBoolean(sp);
            boolean bypassSync = spCls.getField("bypassSync").getBoolean(sp);
            boolean muted = spCls.getField("muted").getBoolean(sp);
            float scale = spCls.getField("volumeScale").getFloat(sp);
            long lastFail = spCls.getField("lastFailedAt").getLong(sp);
            buf("[DBG] 本端: player=" + (hasPlayer ? "在册" : "无")
                    + ", everPlayed=" + everPlayed
                    + ", bypassSync=" + bypassSync
                    + ", 音量=" + Math.round((muted ? 0f : scale) * 100) + "%"
                    + (muted ? "(静音)" : "")
                    + ", 上次失败=" + (lastFail == 0 ? "无" : ((now() - lastFail) / 1000) + "s 前"));
            manualCount += 5;
        } catch (ReflectiveOperationException e) {
            buf("[DBG] 本端状态读取失败: " + e);
            manualCount++;
        }
        player.sendSystemMessage(Component.literal("[DBG] 屏幕快照已写入报告（见复制按钮）")
                .withStyle(ChatFormatting.GRAY));
    }

    // ===== 验证实现区（提交前随验证区一并清空）=====

    // ===== 验证实现区结束 =====

    // ---- 输出辅助（外壳保留）：FAIL 即时红显，其余进缓冲由末尾复制按钮携带 ----

    /** 跨 source set 目标的类加载（common 禁编译期引用 client/server 类） */
    private static Class<?> fqn(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void section(String title) {
        BUF.append("[DBG] ---- ").append(title).append(" ----\n");
    }

    private static void report(Player player, String label, boolean ok) {
        String text = "[DBG] " + label + ": " + (ok ? "PASS" : "FAIL");
        buf(text);
        if (ok) passCount++;
        else {
            failCount++;
            player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
        }
    }

    private static void fail(Player player, String text) {
        buf("[DBG] " + text);
        failCount++;
        player.sendSystemMessage(Component.literal("[DBG] " + text).withStyle(ChatFormatting.RED));
    }

    private static void line(Player player, String text) {
        buf(text);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
    }

    private static void gray(String text) {
        buf("[DBG] " + text);
        manualCount++;
    }

    private static void sample(Player player, String label, String rendered) {
        report(player, label + " 无 key 泄漏", !rendered.contains("kazumiplayer."));
        String text = "[样本] " + label + " → " + rendered;
        buf(text);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    private static void buf(String text) {
        BUF.append(text).append('\n');
    }
}
