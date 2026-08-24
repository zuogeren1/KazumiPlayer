package me.zuogeren.kazumiplayer.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * 聊天组件工具: 可点击的文本组件
 * 26.1.2: ClickEvent/HoverEvent are interfaces, use concrete records
 */
public class ChatComponentUtil {

    /**
     * 创建可点击填充到聊天输入框的文本
     */
    public static MutableComponent suggestable(String text, String command) {
        return suggestable(Component.literal(text), command);
    }

    /** 可本地化组件版本 */
    public static MutableComponent suggestable(Component text, String command) {
        return text.copy()
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.SuggestCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击填充到聊天栏")))
                        .withColor(ChatFormatting.AQUA));
    }

    /**
     * 创建可执行命令的文本
     */
    public static MutableComponent clickable(String text, String command, String hoverText) {
        return clickable(Component.literal(text), command, Component.literal(hoverText));
    }

    /** 可本地化组件版本 */
    public static MutableComponent clickable(Component text, String command, Component hoverText) {
        return text.copy()
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(hoverText))
                        .withColor(ChatFormatting.GREEN));
    }

    /**
     * 普通提示信息
     */
    public static MutableComponent info(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    /**
     * 错误信息
     */
    public static MutableComponent error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** 可本地化悬停文本版本 */
    public static MutableComponent clickable(String text, String command, Component hoverText) {
        return clickable(Component.literal(text), command, hoverText);
    }

    /**
     * 标题信息
     */
    public static MutableComponent header(String text) {
        return header(Component.literal(text));
    }

    public static MutableComponent header(Component text) {
        return text.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }
}
