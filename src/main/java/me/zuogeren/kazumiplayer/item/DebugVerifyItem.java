package me.zuogeren.kazumiplayer.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
        runSuite(player);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !ctx.getLevel().isClientSide()) return InteractionResult.PASS;
        runSuite(player);
        return InteractionResult.SUCCESS;
    }

    private static void runSuite(Player player) {
        BUF.setLength(0);
        passCount = failCount = manualCount = 0;

        line(player, "======== KazumiPlayer DBG ========");
        // ===== 验证区（提交前清空本区域调用，保留外壳）=====
        // （当前无待验证项；修复验证时在此写入检查调用）
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

    // ===== 验证实现区（提交前随验证区一并清空）=====
    // ===== 验证实现区结束 =====

    // ---- 输出辅助（外壳保留）：FAIL 即时红显，其余进缓冲由末尾复制按钮携带 ----

    private static void report(Player player, String label, boolean ok) {
        String text = "[DBG] " + label + ": " + (ok ? "PASS" : "FAIL");
        buf(text);
        if (ok) passCount++;
        else {
            failCount++;
            player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
        }
    }

    private static void pass(Player player, String text) {
        buf("[DBG] " + text);
        passCount++;
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

    private static void buf(String text) {
        BUF.append(text).append('\n');
    }
}
