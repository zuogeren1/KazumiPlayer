package me.zuogeren.kazumiplayer.client.gui;

import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.network.gui.GuiProtocol;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.client.Minecraft;

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
            if (p != null && mc.player != null) KazumiMessages.sendError(mc.player, p.message());
        } else if (GuiProtocol.DATA_PLAY_OK.equals(dataType)) {
            var p = GuiPayloads.fromJson(json, GuiPayloads.PlayOkPayload.class);
            if (p != null && mc.player != null) KazumiMessages.sendInfo(mc.player, p.title());
        }
    }
}
