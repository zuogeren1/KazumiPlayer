package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端: 同步所有屏幕数据
 */
public record ScreenSyncPacket() implements CustomPacketPayload {

    public static final Type<ScreenSyncPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "screen_sync"));

    public static final StreamCodec<FriendlyByteBuf, ScreenSyncPacket> STREAM_CODEC =
            StreamCodec.unit(new ScreenSyncPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
