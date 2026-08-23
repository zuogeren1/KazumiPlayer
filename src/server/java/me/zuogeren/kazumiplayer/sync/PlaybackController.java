package me.zuogeren.kazumiplayer.sync;

import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * 播放控制服务的唯一权威实现（命令层与网络包处理层共用）：
 * 切集 / 暂停恢复 / seek / 实时位置读取。
 *
 * <p>存在意义：这些操作各自是「写 BE NBT + 重置同步组 + 立即广播 + 通知观看者」的
 * 多步原子流程，历史上分散复制在命令层与包处理层，任何一处漏一步都会造成
 * 权威状态与 NBT 分叉（实例 bug：手动切集漏 onPlayStart 导致暂停卡死/时间调整无效）。
 * 新增播放控制功能一律经本类，禁止在调用方手搓组合。
 */
public final class PlaybackController {

    /** 操作结果：success=true 时 detail 为成功描述（集名/时间），false 时为失败原因 */
    public record OpResult(boolean success, String detail) {
        public static OpResult ok(String detail) { return new OpResult(true, detail); }
        public static OpResult fail(String detail) { return new OpResult(false, detail); }
    }

    private static final TypeToken<List<Road>> ROAD_LIST = new TypeToken<>() {};

    private PlaybackController() {}

    // ---- 查询 ----

    /** 实时位置：优先取同步组权威值，无组时回退 NBT 快照 */
    public static long livePosition(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        return g != null ? g.livePositionMillis() : Math.max(0, screen.getSyncPositionMs());
    }

    // ---- 切集 ----

    /**
     * 手动切换上一集/下一集（GUI 按钮、命令共用）。
     * 无剧集数据、越界时返回失败原因；成功 detail 为新集名。
     */
    public static OpResult switchEpisode(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, boolean next) {
        String data = screen.getEpisodeData();
        if (data.isEmpty()) return OpResult.fail("该屏幕无可切换的集数");
        List<Road> roads = JsonUtil.GSON.fromJson(data, ROAD_LIST);
        if (roads == null || roads.isEmpty()) return OpResult.fail("该屏幕无可切换的集数");
        Road road = roads.get(Math.max(0, Math.min(screen.getRoadIndex(), roads.size() - 1)));
        int idx = screen.getEpisodeIndex() + (next ? 1 : -1);
        if (idx < 1) return OpResult.fail("已是第一集");
        if (idx > road.data().size()) return OpResult.fail("已是最后一集");

        String url = road.data().get(idx - 1);
        String name = road.identifier().size() > idx - 1 ? road.identifier().get(idx - 1) : ("第" + idx + "集");
        applyEpisodeSwitch(actor, screenPos, screen, url, screen.getRoadIndex(), idx, JsonUtil.GSON.toJson(roads));
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, screen.getScreenId(), "切换到 " + name);
        return OpResult.ok(name);
    }

    /**
     * 「切集四件套」唯一权威实现：写 NBT → 重置同步组（videoUrl 刷新/位置归零/paused=false）
     * → 同步 WatchingPlayers → 立即广播。自动连播与手动切换都必须走这里。
     */
    public static void applyEpisodeSwitch(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, String url, int roadIdx, int episodeIdx, String roadJson) {
        screen.setPlaybackFull(url, 0, roadIdx, episodeIdx, roadJson);
        UUID sid = screen.getScreenId();
        // 组重置不可省略：缺失时周期广播携带旧集 URL，客户端换片保护会吞掉后续所有暂停/seek
        SyncGroupManager.get().onPlayStart(actor, sid, screenPos, url);
        syncWatchingPlayers(screen);
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
    }

    // ---- 暂停/恢复 ----

    public static OpResult setPaused(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, boolean paused) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g == null) return OpResult.fail("该屏幕未在播放");
        long cur = g.livePositionMillis();
        SyncGroupManager.get().updateState(sid, cur, paused);
        screen.updateSyncPosition(cur);
        screen.setPlaybackPaused(paused);
        // 立即广播暂停/恢复状态（客户端无每秒 NBT 轮询暂停逻辑）
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid, paused ? "暂停了播放" : "恢复了播放");
        return OpResult.ok(paused ? "已暂停" : "已恢复");
    }

    // ---- 时间调整 ----

    /** 绝对跳转（秒表格式 goto、进度条拖动、±相对调整最终都落到这里） */
    public static OpResult seekTo(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, long targetMs) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g == null) return OpResult.fail("该屏幕未在播放");
        long newPos = Math.max(0, targetMs);
        screen.updateSyncPosition(newPos);
        SyncGroupManager.get().updateState(sid, newPos, g.paused);
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid,
            "跳转到 " + KazumiMessages.formatMs(newPos));
        return OpResult.ok(KazumiMessages.formatMs(newPos));
    }

    /** 相对调整（快进/快退秒数），成功 detail 为 "+10s → 0:05" 形式 */
    public static OpResult adjustTime(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, int deltaSec) {
        long target = livePosition(screen) + deltaSec * 1000L;
        OpResult r = seekTo(actor, screenPos, screen, target);
        if (!r.success()) return r;
        String sign = deltaSec >= 0 ? "+" : "";
        return OpResult.ok(sign + deltaSec + "s → " + r.detail());
    }

    // ---- 加入/离开 ----

    /**
     * 加入当前屏幕的同步播放（命令与 GUI 共用）。
     * 三分支：未播放→待机组；播放中→入组并对齐位置；有 URL 无组→异常恢复重建组。
     */
    public static OpResult joinScreen(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen) {
        UUID sid = screen.getScreenId();
        String url = screen.getEpisodeUrl();
        var group = SyncGroupManager.get().getGroup(sid);

        if (url.isEmpty()) {
            // 待机组（computeIfAbsent：已存在的待机组不会重复创建，等开始播放自动生效）
            SyncGroupManager.get().joinStandby(actor, sid, screenPos);
            SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid, "加入了同步播放");
            return OpResult.ok("已加入同步播放（等待播放开始）");
        }
        if (group != null) {
            long currentPos = group.livePositionMillis();
            SyncGroupManager.get().join(actor, sid, url);
            screen.setPlayback(url, currentPos);
            screen.setWatchingPlayers(group.watchingPlayersString());
            SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
            SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid, "加入了同步播放");
            return OpResult.ok("已加入同步播放 (位置: " + currentPos / 1000 + "s)");
        }
        // 有 URL 但无组（异常恢复）：重建组
        SyncGroupManager.get().onPlayStart(actor, sid, screenPos, url);
        screen.setPlayback(url, 0);
        syncWatchingPlayers(screen);
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid, "加入了同步播放");
        return OpResult.ok("已加入同步播放 (位置: 0s)");
    }

    /**
     * 个人离开同步（不整组停止）：保存实时位置到 NBT → 退出组 → 给自己发 PlayStopPacket
     * → 对齐 WatchingPlayers。@param notifyText 通知他人的动词（"停止了播放"/"离开了同步播放"）
     */
    public static void leaveOwn(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, String notifyText) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g != null) {
            screen.updateSyncPosition(g.livePositionMillis());
            SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid, notifyText);
        }
        SyncGroupManager.get().leave(actor.getUUID());
        me.zuogeren.kazumiplayer.network.packet.PlayStopPacket pkt =
            new me.zuogeren.kazumiplayer.network.packet.PlayStopPacket(screenPos);
        PacketDistributor.sendToPlayer(actor, pkt);
        var g2 = SyncGroupManager.get().getGroup(sid);
        screen.setWatchingPlayers(g2 != null ? g2.watchingPlayersString() : "");
    }

    // ---- 内部 ----

    private static void syncWatchingPlayers(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        if (g != null) {
            screen.setWatchingPlayers(g.watchingPlayersString());
        }
    }
}
