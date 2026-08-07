package me.zuogeren.kazumiplayer.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

/**
 * 统一聊天消息工具。所有面向玩家的通知消息都通过此类发送，保证前缀与颜色一致。
 * <p>
 * 前缀 {@code [KazumiPlayer]} 固定为绿色，消息体按严重程度着色：
 * <ul>
 *   <li>{@link #success} — 绿色 §a，操作成功</li>
 *   <li>{@link #info} — 白色 §f，进度/状态通知</li>
 *   <li>{@link #warn} — 黄色 §e，警告/注意</li>
 *   <li>{@link #error} — 红色 §c，错误</li>
 * </ul>
 * <p>
 * 说明：搜索结果的排版行（=== 标题 ===、条目列表等）属于展示文本而非通知，
 * 不应加前缀，改用 {@link ChatComponentUtil} 或直接 {@link Component}。
 */
public final class KazumiMessages {

    private KazumiMessages() {}

    /** 统一前缀：绿色 [KazumiPlayer] + 重置格式 + 空格 */
    private static final String PFX = "§a[KazumiPlayer]§r ";

    // ---- Component 构建 ----

    /** 成功消息组件：绿色 */
    public static MutableComponent success(String msg) {
        return Component.literal(PFX + "§a" + msg);
    }

    /** 普通信息组件：白色 */
    public static MutableComponent info(String msg) {
        return Component.literal(PFX + "§f" + msg);
    }

    /** 警告消息组件：黄色 */
    public static MutableComponent warn(String msg) {
        return Component.literal(PFX + "§e" + msg);
    }

    /** 错误消息组件：红色 */
    public static MutableComponent error(String msg) {
        return Component.literal(PFX + "§c" + msg);
    }

    // ---- 发送到 Player（ServerPlayer 与 LocalPlayer 通用） ----

    public static void sendSuccess(Player player, String msg) {
        player.sendSystemMessage(success(msg));
    }

    public static void sendInfo(Player player, String msg) {
        player.sendSystemMessage(info(msg));
    }

    public static void sendWarn(Player player, String msg) {
        player.sendSystemMessage(warn(msg));
    }

    public static void sendError(Player player, String msg) {
        player.sendSystemMessage(error(msg));
    }

    // ---- 发送到 CommandSourceStack（命令反馈） ----

    public static void sendSuccess(CommandSourceStack src, String msg) {
        src.sendSystemMessage(success(msg));
    }

    public static void sendInfo(CommandSourceStack src, String msg) {
        src.sendSystemMessage(info(msg));
    }

    public static void sendWarn(CommandSourceStack src, String msg) {
        src.sendSystemMessage(warn(msg));
    }

    public static void sendError(CommandSourceStack src, String msg) {
        src.sendSystemMessage(error(msg));
    }

    // ---- 发送到客户端聊天框（无 Player 引用时，如异步回调/静态上下文） ----

    public static void chatSuccess(String msg) {
        net.minecraft.client.Minecraft.getInstance().gui.getChat().addClientSystemMessage(success(msg));
    }

    public static void chatInfo(String msg) {
        net.minecraft.client.Minecraft.getInstance().gui.getChat().addClientSystemMessage(info(msg));
    }

    public static void chatWarn(String msg) {
        net.minecraft.client.Minecraft.getInstance().gui.getChat().addClientSystemMessage(warn(msg));
    }

    public static void chatError(String msg) {
        net.minecraft.client.Minecraft.getInstance().gui.getChat().addClientSystemMessage(error(msg));
    }

    // ---- 展示区块标识（搜索结果/列表排版内容，不加前缀，用分隔线标识来源） ----

    /** 金色分隔线，用于搜索结果/列表展示区块顶部标识来源 */
    public static MutableComponent separator() {
        return Component.literal("§6========KazumiPlayer========");
    }

    // ---- 工具方法 ----

    /** 格式化毫秒为 h:mm:ss 或 m:ss（从 PlayCommands 迁移至此） */
    public static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }
}
