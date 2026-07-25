package me.zuogeren.kazumiplayer.sync;

import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端同步组管理: 每个屏幕一个 SyncGroup
 */
public class SyncGroupManager {
    private static SyncGroupManager instance;

    public static SyncGroupManager get() { return instance; }
    public static void init() { instance = new SyncGroupManager(); }

    private final Map<BlockPos, SyncGroup> groups = new ConcurrentHashMap<>();

    /**
     * 当有玩家开始播放时调用
     */
    public void onPlayStart(ServerPlayer player, BlockPos screenPos, String videoUrl) {
        SyncGroup group = groups.computeIfAbsent(screenPos,
            k -> new SyncGroup(screenPos, videoUrl));
        group.players.add(player.getUUID());
        group.videoUrl = videoUrl;
        group.positionMs = 0;
        group.paused = false;
        group.serverTimestamp = System.currentTimeMillis();
    }

    public void join(ServerPlayer player, BlockPos screenPos, String videoUrl) {
        SyncGroup group = groups.get(screenPos);
        if (group == null) return;
        group.players.add(player.getUUID());
    }

    public void leave(UUID playerId) {
        for (var it = groups.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().players.remove(playerId)) {
                if (entry.getValue().players.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    public SyncGroup getGroup(BlockPos screenPos) {
        return groups.get(screenPos);
    }

    public void updateState(BlockPos screenPos, long positionMs, boolean paused) {
        SyncGroup group = groups.get(screenPos);
        if (group == null) return;
        group.positionMs = positionMs;
        group.paused = paused;
        group.serverTimestamp = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            leave(sp.getUUID());
        }
    }

    public static class SyncGroup {
        public final BlockPos screenPos;
        public String videoUrl;
        public long positionMs;
        public boolean paused;
        public long serverTimestamp;
        public final Set<UUID> players = ConcurrentHashMap.newKeySet();

        SyncGroup(BlockPos pos, String url) {
            this.screenPos = pos;
            this.videoUrl = url;
            this.serverTimestamp = System.currentTimeMillis();
        }
    }
}
