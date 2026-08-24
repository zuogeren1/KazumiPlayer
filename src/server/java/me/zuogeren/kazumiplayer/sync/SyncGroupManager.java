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
        group.anchorEstablished = false; // 等待首个观看者出画后以真实位置重新锚定
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
        broadcastSyncState(screenId, server, false);
    }

    /**
     * 同 {@link #broadcastSyncState(UUID, MinecraftServer)}，forceSeek=true 时客户端
     * 收到广播后无条件跳转到权威位置（seek 类操作专用：±10s 内的位置变化够不到
     * 客户端兜底漂移阈值，必须显式指令才能让全组立即对齐）。
     */
    public void broadcastSyncState(UUID screenId, MinecraftServer server, boolean forceSeek) {
        SyncGroup g = groups.get(screenId);
        if (g == null) return;
        sendSyncState(g, server, MonoClock.millis(), forceSeek);
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
            sendSyncState(g, server, now, false);
        }
    }

    private static void sendSyncState(SyncGroup g, MinecraftServer server, long now, boolean forceSeek) {
        if (server == null || g.players.isEmpty()) return;
        // 权威实时位置: 基准位置 + 未暂停时的流逝时间；首帧锚定前组时钟冻结（等待真实位置校准）
        long livePos = Math.max(0, g.anchorEstablished
            ? g.positionMs + (g.paused ? 0 : now - g.serverTimestamp)
            : g.positionMs);
        SyncStatePacket pkt = new SyncStatePacket(g.screenPos, g.videoUrl, livePos, g.paused, now, forceSeek);
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
                        screen.updateSyncPosition(g.livePositionMillis());
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

    /**
     * 首帧锚定：接受首个观看者出画后的真实播放位置，校准权威时钟。
     * 仅每次切集（onPlayStart）后的第一次上报生效。@return 是否已接受
     */
    public boolean acceptAnchor(UUID screenId, long positionMs) {
        SyncGroup g = groups.get(screenId);
        if (g == null || g.anchorEstablished) return false;
        g.positionMs = Math.max(0, positionMs);
        g.paused = false;
        g.serverTimestamp = MonoClock.millis();
        g.anchorEstablished = true;
        return true;
    }

    public static class SyncGroup {
        public final UUID screenId;
        public final BlockPos screenPos;
        public String videoUrl;
        public long positionMs;
        public boolean paused;
        public long serverTimestamp;
        /** 首帧锚定：true 表示组时钟已由首个出画观看者的真实位置校准，此后正常流逝 */
        public boolean anchorEstablished;
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

        /** 权威实时位置 = 基准位置 + 未暂停时的流逝时间（MonoClock 单调钟，勿用墙钟另行计算）。
         * 首帧锚定前组时钟冻结——播放器尚在解析/缓冲，流逝只会制造假漂移 */
        public long livePositionMillis() {
            if (!anchorEstablished) return positionMs;
            long elapsed = paused ? 0 : MonoClock.millis() - serverTimestamp;
            return positionMs + elapsed;
        }
    }
}
