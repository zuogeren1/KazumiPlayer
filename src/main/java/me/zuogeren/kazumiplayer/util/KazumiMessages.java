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

    // ---- Component 构建（i18n）：key 由客户端语言文件翻译 ----

    /** 统一前缀 + 指定颜色的可本地化消息组件 */
    private static MutableComponent prefixed(net.minecraft.ChatFormatting color, String key, String... args) {
        var body = Component.translatable(key, (Object[]) java.util.Arrays.stream(args)
                .map(Component::literal).toArray(Component[]::new)).withStyle(color);
        return Component.literal("[KazumiPlayer]").withStyle(net.minecraft.ChatFormatting.GREEN)
                .append(Component.literal(" "))
                .append(body);
    }

    /** 参数为现成组件的版本：嵌套 translatable 不经 getString 扁平化，专用服语言表缺失时仍由客户端渲染 */
    private static MutableComponent prefixed(net.minecraft.ChatFormatting color, String key, net.minecraft.network.chat.Component[] args) {
        var body = Component.translatable(key, (Object[]) args).withStyle(color);
        return Component.literal("[KazumiPlayer]").withStyle(net.minecraft.ChatFormatting.GREEN)
                .append(Component.literal(" "))
                .append(body);
    }

    /** 成功消息组件（i18n）：绿色 */
    public static MutableComponent successKey(String key, String... args) {
        return prefixed(net.minecraft.ChatFormatting.GREEN, key, args);
    }

    /** 普通信息组件（i18n）：白色 */
    public static MutableComponent infoKey(String key, String... args) {
        return prefixed(net.minecraft.ChatFormatting.WHITE, key, args);
    }

    /** 警告消息组件（i18n）：黄色 */
    public static MutableComponent warnKey(String key, String... args) {
        return prefixed(net.minecraft.ChatFormatting.YELLOW, key, args);
    }

    /** 错误消息组件（i18n）：红色 */
    public static MutableComponent errorKey(String key, String... args) {
        return prefixed(net.minecraft.ChatFormatting.RED, key, args);
    }

    /** 警告消息组件（i18n）：黄色；参数为现成组件（可嵌套 translatable）。独立命名避免与零参 String 版歧义 */
    public static MutableComponent warnKeyNested(String key, net.minecraft.network.chat.Component... args) {
        return prefixed(net.minecraft.ChatFormatting.YELLOW, key, args);
    }

    /** 普通信息组件（i18n）：白色；参数为现成组件（可嵌套 translatable） */
    public static MutableComponent infoKeyNested(String key, net.minecraft.network.chat.Component... args) {
        return prefixed(net.minecraft.ChatFormatting.WHITE, key, args);
    }

    /** 把任意消息体包装为带统一前缀与黄色的警告反馈 */
    public static MutableComponent warnOf(net.minecraft.network.chat.Component body) {
        return Component.literal(PFX + "§e").append(body);
    }

    /** 把任意消息体包装为带统一前缀与白色的信息反馈（body 可含嵌套 translatable） */
    public static MutableComponent infoOf(net.minecraft.network.chat.Component body) {
        return Component.literal(PFX + "§f").append(body);
    }

    /** 把任意消息体包装为带统一前缀与红色的错误反馈 */
    public static MutableComponent errorOf(net.minecraft.network.chat.Component body) {
        return Component.literal(PFX + "§c").append(body);
    }

    /** 把任意消息体包装为带统一前缀与绿色的成功反馈（body 可含嵌套 translatable） */
    public static MutableComponent successOf(net.minecraft.network.chat.Component body) {
        return Component.literal(PFX + "§a").append(body);
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

    // ---- 发送到 Player（i18n） ----

    public static void sendSuccessKey(Player player, String key, String... args) {
        player.sendSystemMessage(successKey(key, args));
    }

    public static void sendInfoKey(Player player, String key, String... args) {
        player.sendSystemMessage(infoKey(key, args));
    }

    public static void sendWarnKey(Player player, String key, String... args) {
        player.sendSystemMessage(warnKey(key, args));
    }

    public static void sendErrorKey(Player player, String key, String... args) {
        player.sendSystemMessage(errorKey(key, args));
    }

    // ---- 发送到 CommandSourceStack（命令反馈） ----

    public static void sendSuccess(CommandSourceStack src, String msg) {
        src.sendSystemMessage(success(msg));
    }

    /** 命令反馈为现成组件（可含嵌套 translatable）的版本：不经 getString 扁平化 */
    public static void sendSuccess(CommandSourceStack src, net.minecraft.network.chat.Component msg) {
        src.sendSystemMessage(successOf(msg));
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

    // ---- 发送到 CommandSourceStack（i18n） ----

    // ---- 发送到 CommandSourceStack（i18n） ----

    public static void sendSuccessKey(CommandSourceStack src, String key, String... args) {
        src.sendSystemMessage(successKey(key, args));
    }

    public static void sendInfoKey(CommandSourceStack src, String key, String... args) {
        src.sendSystemMessage(infoKey(key, args));
    }

    public static void sendWarnKey(CommandSourceStack src, String key, String... args) {
        src.sendSystemMessage(warnKey(key, args));
    }

    public static void sendErrorKey(CommandSourceStack src, String key, String... args) {
        src.sendSystemMessage(errorKey(key, args));
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
