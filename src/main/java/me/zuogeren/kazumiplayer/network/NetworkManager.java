package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.GuiActionPacket;
import me.zuogeren.kazumiplayer.network.packet.GuiDataPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayUrlPacket;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.ScreenSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SpeakerConnectPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncResponsePacket;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通信注册中心
 * 26.1.x: 使用 PayloadRegistrar 替代旧的 SimpleChannel
 */
public class NetworkManager {

    private static final String VERSION = "1";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        // 服务端 -> 客户端
        // 处理逻辑由 PacketDispatcher 分派到客户端/服务端模块注入的处理器
        registrar.playToClient(RuleSyncPacket.TYPE, RuleSyncPacket.STREAM_CODEC, PacketDispatcher::dispatchClient);
        registrar.playToClient(ScreenSyncPacket.TYPE, ScreenSyncPacket.STREAM_CODEC, PacketDispatcher::dispatchClient);
        registrar.playToClient(SyncStatePacket.TYPE, SyncStatePacket.STREAM_CODEC, PacketDispatcher::dispatchClient);
        registrar.playToClient(PlayStopPacket.TYPE, PlayStopPacket.STREAM_CODEC, PacketDispatcher::dispatchClient);
        registrar.playToServer(NextEpisodePacket.TYPE, NextEpisodePacket.STREAM_CODEC, PacketDispatcher::dispatchServer);
        registrar.playToServer(PlaybackControlPacket.TYPE, PlaybackControlPacket.STREAM_CODEC, PacketDispatcher::dispatchServer);
        registrar.playToServer(SpeakerConnectPacket.TYPE, SpeakerConnectPacket.STREAM_CODEC, PacketDispatcher::dispatchServer);
        registrar.playToServer(PlayUrlPacket.TYPE, PlayUrlPacket.STREAM_CODEC, PacketDispatcher::dispatchServer);
        registrar.playToServer(GuiActionPacket.TYPE, GuiActionPacket.STREAM_CODEC, PacketDispatcher::dispatchServer);
        registrar.playToClient(GuiDataPacket.TYPE, GuiDataPacket.STREAM_CODEC, PacketDispatcher::dispatchClient);
        registrar.playToServer(TimeSyncPacket.TYPE, TimeSyncPacket.STREAM_CODEC, PacketDispatcher::dispatchServer);
        registrar.playToClient(TimeSyncResponsePacket.TYPE, TimeSyncResponsePacket.STREAM_CODEC, PacketDispatcher::dispatchClient);
    }
}
