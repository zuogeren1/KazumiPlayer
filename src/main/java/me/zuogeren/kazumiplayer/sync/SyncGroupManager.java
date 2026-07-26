package me.zuogeren.kazumiplayer.sync;

import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端同步组管理: 每个屏幕一个 SyncGroup，以 UUID 为 key
 */
public class SyncGroupManager {
    private static SyncGroupManager instance;

    public static SyncGroupManager get() { return instance; }
    public static void init() { instance = new SyncGroupManager(); }

    private final Map<UUID, SyncGroup> groups = new ConcurrentHashMap<>();

    /**
     * 当有玩家开始播放时调用
     */
    public void onPlayStart(ServerPlayer player, UUID screenId, BlockPos screenPos, String videoUrl) {
        SyncGroup group = groups.computeIfAbsent(screenId,
            k -> new SyncGroup(screenId, screenPos, videoUrl));
        group.players.add(player.getUUID());
        group.videoUrl = videoUrl;
        group.positionMs = 0;
        group.paused = false;
        group.serverTimestamp = System.currentTimeMillis();
    }

    public void join(ServerPlayer player, UUID screenId, String videoUrl) {
        SyncGroup group = groups.get(screenId);
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

    public void leaveByScreenId(UUID screenId) {
        groups.remove(screenId);
    }

    public SyncGroup getGroup(UUID screenId) {
        return groups.get(screenId);
    }

    public void updateState(UUID screenId, long positionMs, boolean paused) {
        SyncGroup group = groups.get(screenId);
        if (group == null) return;
        group.positionMs = positionMs;
        group.paused = paused;
        group.serverTimestamp = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // 进服时同步已安装的规则到客户端
            var rm = new RuleManager(FMLPaths.CONFIGDIR.get());
            rm.loadAll();
            var rules = rm.listAll();
            if (!rules.isEmpty()) {
                String json = me.zuogeren.kazumiplayer.util.JsonUtil.GSON.toJson(
                    rules.stream().map(rm::get).filter(r -> r != null).toList());
                PacketDistributor.sendToPlayer(sp, new RuleSyncPacket(json));
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            UUID playerId = sp.getUUID();
            // 离开前：收集受影响的屏幕列表，并保存实时位置
            List<BlockPos> affectedScreens = new ArrayList<>();
            for (var entry : groups.entrySet()) {
                SyncGroup g = entry.getValue();
                if (g.players.contains(playerId)) {
                    affectedScreens.add(g.screenPos);
                    var be = sp.level().getBlockEntity(g.screenPos);
                    if (be instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity screen) {
                        long elapsed = g.paused ? 0 : System.currentTimeMillis() - g.serverTimestamp;
                        screen.updateSyncPosition(g.positionMs + elapsed);
                    }
                }
            }
            leave(playerId);
            // 更新所有受影响屏幕的 WatchingPlayers（组已被删的会清空）
            for (BlockPos pos : affectedScreens) {
                var be = sp.level().getBlockEntity(pos);
                if (be instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity screen) {
                    SyncGroup g = groups.get(screen.getScreenId());
                    screen.setWatchingPlayers(g != null
                        ? String.join(",", g.players.stream().map(java.util.UUID::toString).toList())
                        : "");
                }
            }
        }
    }

    public static class SyncGroup {
        public final UUID screenId;
        public final BlockPos screenPos;
        public String videoUrl;
        public long positionMs;
        public boolean paused;
        public long serverTimestamp;
        public final Set<UUID> players = ConcurrentHashMap.newKeySet();

        SyncGroup(UUID screenId, BlockPos pos, String url) {
            this.screenId = screenId;
            this.screenPos = pos;
            this.videoUrl = url;
            this.serverTimestamp = System.currentTimeMillis();
        }
    }
}
