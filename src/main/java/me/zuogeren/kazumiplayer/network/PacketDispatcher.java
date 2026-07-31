package me.zuogeren.kazumiplayer.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 网络包处理器分派中心（common）。
 * 数据包注册由 common 的 NetworkManager 完成，具体处理逻辑由
 * 客户端/服务端模块在初始化时分别注入，避免 common 依赖任一 side。
 */
public final class PacketDispatcher {
    private static volatile IClientPacketHandler clientHandler;
    private static volatile IServerPacketHandler serverHandler;

    private PacketDispatcher() {}

    public static void setClientHandler(IClientPacketHandler handler) {
        clientHandler = handler;
    }

    public static void setServerHandler(IServerPacketHandler handler) {
        serverHandler = handler;
    }

    /** 注册到 playToClient 的转发方法（S→C 包） */
    public static <T extends CustomPacketPayload> void dispatchClient(T packet, IPayloadContext context) {
        IClientPacketHandler h = clientHandler;
        if (h != null) {
            h.handle(packet, context);
        }
    }

    /** 注册到 playToServer 的转发方法（C→S 包） */
    public static <T extends CustomPacketPayload> void dispatchServer(T packet, IPayloadContext context) {
        IServerPacketHandler h = serverHandler;
        if (h != null) {
            h.handle(packet, context);
        }
    }
}
