package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.NextEpisodePacket;
import me.zuogeren.kazumiplayer.network.packet.PlaybackControlPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStartPacket;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.ScreenSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SpeakerConnectPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
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
        registrar.playToClient(RuleSyncPacket.TYPE, RuleSyncPacket.STREAM_CODEC, RuleSyncPacket::handle);
        registrar.playToClient(ScreenSyncPacket.TYPE, ScreenSyncPacket.STREAM_CODEC, ScreenSyncPacket::handle);
        registrar.playToClient(PlayStartPacket.TYPE, PlayStartPacket.STREAM_CODEC, PlayStartPacket::handle);
        registrar.playToClient(SyncStatePacket.TYPE, SyncStatePacket.STREAM_CODEC, SyncStatePacket::handle);
        registrar.playToClient(PlayStopPacket.TYPE, PlayStopPacket.STREAM_CODEC, PlayStopPacket::handle);
        registrar.playToServer(NextEpisodePacket.TYPE, NextEpisodePacket.STREAM_CODEC, NextEpisodePacket::handle);
        registrar.playToServer(PlaybackControlPacket.TYPE, PlaybackControlPacket.STREAM_CODEC, PlaybackControlPacket::handle);
        registrar.playToServer(SpeakerConnectPacket.TYPE, SpeakerConnectPacket.STREAM_CODEC, SpeakerConnectPacket::handle);
    }
}
