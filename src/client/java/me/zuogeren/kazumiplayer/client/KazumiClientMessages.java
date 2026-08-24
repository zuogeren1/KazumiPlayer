package me.zuogeren.kazumiplayer.client;

import net.minecraft.client.Minecraft;

/**
 * 客户端本地聊天消息工具（无 Player 引用场景：异步回调/静态上下文）。
 * 依赖 Minecraft 客户端类，仅存在于 client source set——common 侧禁止引用。
 */
public final class KazumiClientMessages {

    private KazumiClientMessages() {}

    public static void chatSuccess(String msg) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(me.zuogeren.kazumiplayer.util.KazumiMessages.success(msg));
    }

    public static void chatInfo(String msg) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(me.zuogeren.kazumiplayer.util.KazumiMessages.info(msg));
    }

    public static void chatWarn(String msg) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(me.zuogeren.kazumiplayer.util.KazumiMessages.warn(msg));
    }

    public static void chatError(String msg) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(me.zuogeren.kazumiplayer.util.KazumiMessages.error(msg));
    }
}
