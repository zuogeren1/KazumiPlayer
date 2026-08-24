package me.zuogeren.kazumiplayer.client.gui;

import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.network.gui.GuiProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * GUI 通用数据通道的客户端桥：GuiDataPacket → 已打开的 KazumiPlayerScreen。
 * 界面未打开时（响应迟到）把提示落到本地聊天栏。
 */
public final class GuiClientState {

    public interface Listener {
        void onGuiData(String dataType, String json);
    }

    private static volatile Listener listener;

    private GuiClientState() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    public static Listener getListener() {
        return listener;
    }

    /** 仅当 l 仍是当前监听者时清空（Screen 切换时序防御，避免误清新界面的注册） */
    public static void removeListenerIfOwner(Listener l) {
        if (listener == l) listener = null;
    }

    /** 在主线程被 ClientPacketHandlers 调用 */
    public static void onData(String dataType, String json) {
        Listener l = listener;
        if (l != null) {
            l.onGuiData(dataType, json);
            return;
        }
        // 界面已关闭：提示落到聊天栏
        Minecraft mc = Minecraft.getInstance();
        if (GuiProtocol.DATA_ERROR.equals(dataType)) {
            var p = GuiPayloads.fromJson(json, GuiPayloads.ErrorPayload.class);
            if (p != null && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[KazumiPlayer]").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(" "))
                    .append(p.toComponent().copy().withStyle(ChatFormatting.RED)));
            }
        } else if (GuiProtocol.DATA_PLAY_OK.equals(dataType)) {
            var p = GuiPayloads.fromJson(json, GuiPayloads.PlayOkPayload.class);
            if (p != null && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[KazumiPlayer]").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(" "))
                    .append(Component.literal(p.title() == null ? "" : p.title())));
            }
        }
    }
}
