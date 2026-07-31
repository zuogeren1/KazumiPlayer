package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端: 同步已安装的规则列表 (JSON 字符串)
 */
public record RuleSyncPacket(String rulesJson) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RuleSyncPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "rule_sync"));

    public static final StreamCodec<FriendlyByteBuf, RuleSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RuleSyncPacket::rulesJson,
                    RuleSyncPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
