package me.zuogeren.kazumiplayer.sync;

import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import net.minecraft.network.chat.Component;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
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

    /** 操作结果：success=true 时 detail 为成功描述（集名/时间），false 时为失败原因；文本可本地化 */
    public record OpResult(boolean success, net.minecraft.network.chat.Component detail) {
        public static OpResult ok(net.minecraft.network.chat.Component detail) { return new OpResult(true, detail); }
        public static OpResult fail(net.minecraft.network.chat.Component detail) { return new OpResult(false, detail); }
        public static OpResult okLiteral(String detail) { return ok(net.minecraft.network.chat.Component.literal(detail)); }
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
     * 线路/集数序号统一钳制：roadIdxZeroBased 越界钳入有效线路，episode（1-based）钳入该线路集数范围。
     * 返回 {roadIdx, episodeIdx}；调用方必须用同一返回值取 URL/生成文案/落盘，杜绝"URL 取钳制值而索引存原始值"的分叉。
     */
    public static int[] clampEpisodePosition(List<Road> roads, int roadIdxZeroBased, int episode) {
        int roadIdx = Math.max(0, Math.min(roadIdxZeroBased, roads.size() - 1));
        Road road = roads.get(roadIdx);
        int size = road.data().size();
        int idx = size == 0 ? 1 : Math.max(1, Math.min(episode, size));
        return new int[]{roadIdx, idx};
    }

    /**
     * 规则剧集播放的唯一入口（命令 /kazumi play 与 GUI play_episode 共用）：
     * 写完整 NBT（RoadIndex/EpisodeData 持久化，自动连播依赖）→ 重置同步组 → 同步观看者
     * → 设置番剧标题 → 立即广播。禁止调用方手搓组合。
     *
     * @param roadIdx / episodeIdx 必须来自 {@link #clampEpisodePosition} 的返回值
     */
    public static void playFromSearch(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, List<Road> roads, int roadIdx, int episodeIdx, String title) {
        Road road = roads.get(roadIdx);
        String url = road.data().get(episodeIdx - 1);
        screen.setPlaybackFull(url, 0, roadIdx, episodeIdx, JsonUtil.GSON.toJson(roads));
        UUID sid = screen.getScreenId();
        SyncGroupManager.get().onPlayStart(actor, sid, screenPos, url);
        syncWatchingPlayers(screen);
        screen.setPlayingTitle(title);
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
    }


    /**
     * 手动切换上一集/下一集（GUI 按钮、命令共用）。
     * 无剧集数据、越界时返回失败原因；成功 detail 为新集名。
     */
    public static OpResult switchEpisode(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, boolean next) {
        String data = screen.getEpisodeData();
        if (data.isEmpty()) return OpResult.fail(Component.translatable("kazumiplayer.err.no_episodes"));
        List<Road> roads = JsonUtil.GSON.fromJson(data, ROAD_LIST);
        if (roads == null || roads.isEmpty()) return OpResult.fail(Component.translatable("kazumiplayer.err.no_episodes"));
        Road road = roads.get(Math.max(0, Math.min(screen.getRoadIndex(), roads.size() - 1)));
        int idx = screen.getEpisodeIndex() + (next ? 1 : -1);
        if (idx < 1) return OpResult.fail(Component.translatable("kazumiplayer.err.already_first"));
        if (idx > road.data().size()) return OpResult.fail(Component.translatable("kazumiplayer.err.already_last"));

        String url = road.data().get(idx - 1);
        // 集名回退用嵌套组件：getString 在专用服上会返回 key 原文（语言表只在客户端）
        Component name = road.identifier().size() > idx - 1
                ? Component.literal(road.identifier().get(idx - 1))
                : Component.translatable("kazumiplayer.gui.main.episode_n", idx);
        applyEpisodeSwitch(actor, screenPos, screen, url, screen.getRoadIndex(), idx, JsonUtil.GSON.toJson(roads));
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, screen.getScreenId(),
                KazumiMessages.warnKeyNested("kazumiplayer.msg.notify.switched", name));
        return OpResult.ok(name);
    }

    /**
     * 「切集四件套」唯一权威实现：写 NBT → 重置同步组（videoUrl 刷新/位置归零/paused=false）
     * → 同步 WatchingPlayers → 立即广播。自动连播与手动切换都必须走这里。
     *
     * <p>直链队列语义：目标序号之前的项目视为已播完，随切换一并移出队列——
     * 列表从新当前项起重写、episodeIndex 重置为 1（自动下一集/手动下一集/GUI 推进路径一致生效）；
     * 规则剧集数据不受影响，保留完整列表供 prev/选集回跳。
     */
    public static void applyEpisodeSwitch(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, String url, int roadIdx, int episodeIdx, String roadJson) {
        if (episodeIdx > 1 && DirectLinkQueue.isQueueData(roadJson)) {
            List<String> urls = DirectLinkQueue.parseUrls(roadJson);
            if (urls != null && episodeIdx <= urls.size()) {
                urls = new ArrayList<>(urls.subList(episodeIdx - 1, urls.size()));
                roadJson = DirectLinkQueue.buildRoadJson(urls);
                episodeIdx = 1;
            }
        }
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
        if (g == null) return OpResult.fail(Component.translatable("kazumiplayer.err.not_playing"));
        long cur = g.livePositionMillis();
        SyncGroupManager.get().updateState(sid, cur, paused);
        screen.updateSyncPosition(cur);
        screen.setPlaybackPaused(paused);
        // 立即广播暂停/恢复状态（客户端无每秒 NBT 轮询暂停逻辑）
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid, Component.translatable(
                paused ? "kazumiplayer.msg.notify.paused" : "kazumiplayer.msg.notify.resumed"));
        return OpResult.ok(Component.translatable(paused
                ? "kazumiplayer.msg.ok.paused" : "kazumiplayer.msg.ok.resumed"));
    }

    // ---- 时间调整 ----

    /** 绝对跳转（秒表格式 goto、进度条拖动、±相对调整最终都落到这里） */
    public static OpResult seekTo(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, long targetMs) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g == null) return OpResult.fail(Component.translatable("kazumiplayer.err.not_playing"));
        long newPos = Math.max(0, targetMs);
        screen.updateSyncPosition(newPos);
        SyncGroupManager.get().updateState(sid, newPos, g.paused);
        // forceSeek 广播：全组收到后立即跳转，不依赖客户端兜底漂移校正
        // （±10s 内的位移永远够不到漂移阈值，无此指令则 seek 对画面无效且永不收敛）
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer(), true);
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid,
            Component.translatable("kazumiplayer.msg.notify.seeked", KazumiMessages.formatMs(newPos)));
        return OpResult.ok(Component.literal(KazumiMessages.formatMs(newPos)));
    }

    /** 相对调整（快进/快退秒数），成功 detail 为 "+10s → 0:05" 形式 */
    public static OpResult adjustTime(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, int deltaSec) {
        long target = livePosition(screen) + deltaSec * 1000L;
        OpResult r = seekTo(actor, screenPos, screen, target);
        if (!r.success()) return r;
        String sign = deltaSec >= 0 ? "+" : "";
        return OpResult.ok(Component.literal(sign + deltaSec + "s → " + r.detail().getString()));
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
            SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid,
                Component.translatable("kazumiplayer.msg.notify.joined"));
            return OpResult.ok(Component.translatable("kazumiplayer.msg.ok.joined_wait"));
        }
        if (group != null) {
            long currentPos = group.livePositionMillis();
            SyncGroupManager.get().join(actor, sid, url);
            screen.setPlayback(url, currentPos);
            screen.setWatchingPlayers(group.watchingPlayersString());
            SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
            SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid,
                Component.translatable("kazumiplayer.msg.notify.joined"));
            return OpResult.ok(Component.translatable("kazumiplayer.msg.ok.joined_pos", String.valueOf(currentPos / 1000)));
        }
        // 有 URL 但无组（异常恢复）：重建组
        SyncGroupManager.get().onPlayStart(actor, sid, screenPos, url);
        screen.setPlayback(url, 0);
        syncWatchingPlayers(screen);
        SyncGroupManager.get().broadcastSyncState(sid, actor.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(actor, screenPos, sid,
                Component.translatable("kazumiplayer.msg.notify.joined"));
        return OpResult.ok(Component.translatable("kazumiplayer.msg.ok.joined_pos", "0"));
    }

    /**
     * 个人离开同步（不整组停止）：保存实时位置到 NBT → 退出组 → 给自己发 PlayStopPacket
     * → 对齐 WatchingPlayers。@param notifyText 通知他人的动词短语，可本地化组件
     * （服务端语言表不含 mod 词条，通知文本禁止 getString 扁平化）
     */
    public static void leaveOwn(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, Component notifyText) {
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

    /** String 便捷重载：按字面量包装后委托组件版（历史签名兼容） */
    public static void leaveOwn(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, String notifyText) {
        leaveOwn(actor, screenPos, screen, Component.literal(notifyText));
    }

    // ---- 整屏停止 ----

    /**
     * 整屏停止的唯一权威实现（自动播完/GUI 停止屏幕/命令 screen stop/队列耗尽共用）：
     * 保存实时位置 → 清 NBT（含 WatchingPlayers，防止残留陈旧观看者）→ 删组
     * → 向全部在线观看者发 PlayStopPacket（含发起者自身，保证其客户端同步停播）
     * → 向除发起者外的观看者发通知。
     *
     * @param actor      发起者；null 表示无人发起的自动事件（播完/队列耗尽），此时通知发给全组
     * @param actionText 通知动词短语（如"停止了屏幕播放"/"播放已结束"），可本地化组件
     */
    public static void stopScreen(ServerPlayer actor, BlockPos screenPos,
            VideoScreenBlockEntity screen, Component actionText) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        if (g != null) {
            screen.updateSyncPosition(g.livePositionMillis());
        }
        List<UUID> watchers = g != null ? List.copyOf(g.players) : List.of();

        screen.clearPlayback();
        screen.setWatchingPlayers("");
        SyncGroupManager.get().leaveByScreenId(sid);

        if (!(screen.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        var server = serverLevel.getServer();
        Component body = actor != null
                ? Component.literal("").append(actor.getName())
                    .append(Component.literal(" ")).append(actionText)
                : actionText;
        for (UUID pid : watchers) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p == null) continue;
            PacketDistributor.sendToPlayer(p, new me.zuogeren.kazumiplayer.network.packet.PlayStopPacket(screenPos));
            if (actor == null || p != actor) {
                p.sendSystemMessage(KazumiMessages.infoOf(body));
            }
        }
    }

    // ---- 内部 ----

    private static void syncWatchingPlayers(VideoScreenBlockEntity screen) {
        var g = SyncGroupManager.get().getGroup(screen.getScreenId());
        if (g != null) {
            screen.setWatchingPlayers(g.watchingPlayersString());
        }
    }
}
