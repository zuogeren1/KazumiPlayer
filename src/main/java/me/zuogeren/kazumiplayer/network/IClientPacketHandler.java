package me.zuogeren.kazumiplayer.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端网络包处理器（由客户端模块注入）。
 * 处理所有 S→C 数据包的接收逻辑。
 */
public interface IClientPacketHandler {
    void handle(CustomPacketPayload packet, IPayloadContext context);
}
