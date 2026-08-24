package me.zuogeren.kazumiplayer.network;

/**
 * C→S 自定义包发送钩子：common 代码经此发送自定义包而不直接引用客户端连接类。
 * 客户端模块构造时注入真实实现（包装 ServerboundCustomPayloadPacket）；
 * 未注入（专用服）时静默丢弃，与 PacketDispatcher 同策略。
 */
public final class ClientPacketSender {

    public interface Sender {
        void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload);
    }

    private static volatile Sender sender;

    private ClientPacketSender() {}

    public static void set(Sender s) { sender = s; }

    public static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var s = sender;
        if (s != null) s.send(payload);
    }
}
