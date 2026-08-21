package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C: GUI 通用数据通道（dataType + JSON payload，常量见 GuiProtocol）。
 * 客户端处理逻辑见 ClientPacketHandlers → KazumiPlayerScreen。
 */
public record GuiDataPacket(String dataType, String payloadJson) implements CustomPacketPayload {

    public static final Type<GuiDataPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "gui_data"));

    public static final StreamCodec<FriendlyByteBuf, GuiDataPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuiDataPacket::dataType,
            ByteBufCodecs.STRING_UTF8, GuiDataPacket::payloadJson,
            GuiDataPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
