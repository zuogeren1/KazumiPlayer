package me.zuogeren.kazumiplayer.sync;

import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.SyncStatePacket;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端同步组管理: 每个屏幕一个 SyncGroup，以 UUID 为 key
 */
public class SyncGroupManager {
    /** 周期广播间隔 (tick): 5 秒 @20tps */
    private static final int SYNC_INTERVAL_TICKS = 100;

    private static SyncGroupManager instance;
    private static RuleManager ruleManager;

    public static SyncGroupManager get() { return instance; }

    /** @param ruleManager 复用 KazumiPlayerServer 初始化的单例（进服广播规则用） */
    public static void init(RuleManager ruleManager) {
        instance = new SyncGroupManager();
        SyncGroupManager.ruleManager = ruleManager;
    }

    private final Map<UUID, SyncGroup> groups = new ConcurrentHashMap<>();
    private int syncTickCounter;

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
        group.serverTimestamp = MonoClock.millis();
    }

    public void join(ServerPlayer player, UUID screenId, String videoUrl) {
        SyncGroup group = groups.get(screenId);
        if (group == null) return;
        group.players.add(player.getUUID());
    }

    /** 加入待机（屏幕未在播放时），创建空 URL 组；等播放开始后自动生效 */
    public void joinStandby(ServerPlayer player, UUID screenId, BlockPos screenPos) {
        SyncGroup group = groups.computeIfAbsent(screenId,
            k -> new SyncGroup(screenId, screenPos, ""));
        group.players.add(player.getUUID());
        // 同步 WatchingPlayers
        var be = player.level().getBlockEntity(screenPos);
        if (be instanceof me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity screen) {
            screen.setWatchingPlayers(group.watchingPlayersString());
        }
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
        group.serverTimestamp = MonoClock.millis();
    }

    /**
     * 向指定屏幕组内所有观看者广播权威播放状态（即时操作后调用）。
     */
    public void broadcastSyncState(UUID screenId, MinecraftServer server) {
        SyncGroup g = groups.get(screenId);
        if (g == null) return;
        sendSyncState(g, server, MonoClock.millis());
    }

    /**
     * 周期任务：每 SYNC_INTERVAL_TICKS tick 向所有活跃播放组广播一次权威位置，
     * 客户端据此校正漂移（替代原来的客户端每秒轮询 NBT seek）。
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (++syncTickCounter % SYNC_INTERVAL_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        long now = MonoClock.millis();
        for (SyncGroup g : groups.values()) {
            if (g.players.isEmpty()) continue;
            if (g.videoUrl == null || g.videoUrl.isEmpty()) continue; // 待机组不广播
            sendSyncState(g, server, now);
        }
    }

    private static void sendSyncState(SyncGroup g, MinecraftServer server, long now) {
        if (server == null || g.players.isEmpty()) return;
        // 权威实时位置: 基准位置 + 未暂停时的流逝时间
        long elapsed = g.paused ? 0 : now - g.serverTimestamp;
        long livePos = Math.max(0, g.positionMs + elapsed);
        SyncStatePacket pkt = new SyncStatePacket(g.screenPos, g.videoUrl, livePos, g.paused, now);
        int sent = 0;
        for (UUID pid : g.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) {
                PacketDistributor.sendToPlayer(p, pkt);
                sent++;
            }
        }
        if (sent > 0) {
            KazumiLog.sync.debug("Broadcast sync state for screen {} at {}ms (paused={}) to {} players",
                g.screenId, livePos, g.paused, sent);
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // 进服时同步已安装的规则到客户端（读 KazumiPlayerServer 初始化的单例，
            // 勿在此 new RuleManager：既重复读盘，又产生与命令/GUI 路径不同步的第二实例）
            if (ruleManager == null) return;
            var rules = ruleManager.listAll();
            if (!rules.isEmpty()) {
                String json = me.zuogeren.kazumiplayer.util.JsonUtil.GSON.toJson(
                    rules.stream().map(ruleManager::get).filter(r -> r != null).toList());
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
                        long elapsed = g.paused ? 0 : MonoClock.millis() - g.serverTimestamp;
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
                    screen.setWatchingPlayers(g != null ? g.watchingPlayersString() : "");
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
            this.serverTimestamp = MonoClock.millis();
        }

        /** 观看者 UUID 列表 → 逗号分隔字符串（写入 BE 的 WatchingPlayers NBT） */
        public String watchingPlayersString() {
            return String.join(",", players.stream().map(UUID::toString).toList());
        }
    }
}
