package me.zuogeren.kazumiplayer.network.packet;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SyncStatePacket(
        BlockPos screenPos, String videoUrl, long positionMs,
        boolean paused, long serverTimestamp) implements CustomPacketPayload {

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

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(packet.screenPos) instanceof VideoScreenBlockEntity screen) {
                if (screen.player == null) return;
                // 计算实际播放位置: 服务端时间戳 + 本地流逝时间
                long elapsed = packet.paused ? 0 : System.currentTimeMillis() - packet.serverTimestamp;
                long targetPos = packet.positionMs + elapsed;
                screen.player.seek(targetPos);
                if (packet.paused) screen.player.pause();
                else screen.player.resume();
            }
        });
    }
}
