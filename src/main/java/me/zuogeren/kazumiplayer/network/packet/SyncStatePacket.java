package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 -> 客户端: 同步播放状态 (时间戳、暂停等)
 */
public record SyncStatePacket(
        BlockPos screenPos,
        String videoUrl,
        long positionMs,
        boolean paused,
        long serverTimestamp) implements CustomPacketPayload {

    public static final Type<SyncStatePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(KazumiPlayer.MODID, "sync_state"));

    public static final StreamCodec<FriendlyByteBuf, SyncStatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SyncStatePacket::screenPos,
                    ByteBufCodecs.STRING_UTF8, SyncStatePacket::videoUrl,
                    ByteBufCodecs.VAR_LONG, SyncStatePacket::positionMs,
                    ByteBufCodecs.BOOL, SyncStatePacket::paused,
                    ByteBufCodecs.VAR_LONG, SyncStatePacket::serverTimestamp,
                    SyncStatePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Phase 6: 同步客户端播放位置
        });
    }
}
