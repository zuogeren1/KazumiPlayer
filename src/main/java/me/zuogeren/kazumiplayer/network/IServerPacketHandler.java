package me.zuogeren.kazumiplayer.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端网络包处理器（由服务端模块注入）。
 * 处理所有 C→S 数据包的接收逻辑。
 */
public interface IServerPacketHandler {
    void handle(CustomPacketPayload packet, IPayloadContext context);
}
