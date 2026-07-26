package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C→S: 连接/断开音响与屏幕
 */
public record SpeakerConnectPacket(BlockPos speakerPos, BlockPos screenPos, UUID screenId, boolean connect) implements CustomPacketPayload {

    public static final Type<SpeakerConnectPacket> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "speaker_connect"));

    public static final StreamCodec<FriendlyByteBuf, SpeakerConnectPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SpeakerConnectPacket::speakerPos,
            BlockPos.STREAM_CODEC, SpeakerConnectPacket::screenPos,
            net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), SpeakerConnectPacket::screenId,
            net.minecraft.network.codec.ByteBufCodecs.BOOL, SpeakerConnectPacket::connect,
            SpeakerConnectPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SpeakerConnectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;

            // 校验两个方块都存在
            var spkBe = sp.level().getBlockEntity(packet.speakerPos);
            if (!(spkBe instanceof SpeakerBlockEntity spk)) return;
            var scrBe = sp.level().getBlockEntity(packet.screenPos);
            if (!(scrBe instanceof VideoScreenBlockEntity screen)) return;

            // 校验 screenId 匹配
            if (!packet.screenId.equals(screen.getScreenId())) return;

            if (packet.connect) {
                // 建立连接：双向更新
                spk.setLink(packet.screenId, packet.screenPos);
                screen.addConnectedSpeaker(packet.speakerPos);
                sp.sendSystemMessage(Component.literal("§a音响已连接到屏幕 ("
                    + packet.screenPos.getX() + ", " + packet.screenPos.getY() + ", " + packet.screenPos.getZ() + ")"));
            } else {
                // 断开连接：双向清空
                spk.clearLink();
                screen.removeConnectedSpeaker(packet.speakerPos);
                sp.sendSystemMessage(Component.literal("§e音响已断开连接"));
            }
        });
    }
}
