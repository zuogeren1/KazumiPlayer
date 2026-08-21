package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C→S: GUI 通用请求通道（action + JSON payload，常量见 GuiProtocol）。
 * screenPos 为 GUI 绑定的屏幕方块。处理逻辑见服务端模块 GuiRequestHandlers。
 */
public record GuiActionPacket(BlockPos screenPos, String action, String payloadJson) implements CustomPacketPayload {

    public static final Type<GuiActionPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "gui_action"));

    public static final StreamCodec<FriendlyByteBuf, GuiActionPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GuiActionPacket::screenPos,
            ByteBufCodecs.STRING_UTF8, GuiActionPacket::action,
            ByteBufCodecs.STRING_UTF8, GuiActionPacket::payloadJson,
            GuiActionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
