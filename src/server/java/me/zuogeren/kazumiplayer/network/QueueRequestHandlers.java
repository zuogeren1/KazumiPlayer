package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 直链队列的服务端操作（queue_add/jump/move/remove）。
 * 队列复用 EpisodeData 存合成 Road（见 {@link DirectLinkQueue}），与规则剧集互斥；
 * GUI 通道与 /kazumi play-url 命令共用同一套语义。
 * 各操作返回错误消息（调用方决定展示渠道），null 表示成功。
 */
public final class QueueRequestHandlers {

    private QueueRequestHandlers() {}

    /**
     * 提交直链：屏幕空闲则以这些 URL 起播（重置队列），队列播放中则追加到队尾，
     * 规则剧集播放中拒绝。URL 需预先 trim，此处在做非空/长度过滤。
     */
    public static String submit(ServerPlayer sp, BlockPos screenPos, List<String> rawUrls) {
        List<String> urls = new ArrayList<>();
        for (String u : rawUrls) {
            String t = u == null ? "" : u.trim();
            if (!t.isEmpty() && t.length() <= 2048) urls.add(t);
        }
        if (urls.isEmpty()) return "链接为空";

        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return "目标方块不是屏幕";
        UUID sid = screen.getScreenId();

        // 空闲（含任何残留数据）：直接以本次提交起播
        if (screen.getEpisodeUrl().isEmpty()) {
            playItemAt(sp, screenPos, screen, urls, 1, "开始播放直链视频");
            KazumiMessages.sendSuccess(sp, "已开始播放: " + urls.get(0));
            KazumiLog.network.info("Queue start at {}: {} item(s)", screenPos.toShortString(), urls.size());
            return null;
        }

        List<String> existing = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (existing == null) {
            return "当前屏幕正在播放剧集，直链无法加入队列";
        }
        // 重复检测：已在队列中的链接跳过；全部重复则直接提示
        List<String> fresh = new ArrayList<>(urls);
        fresh.removeAll(existing);
        if (fresh.isEmpty()) {
            return "该链接已在队列中";
        }
        if (existing.size() + fresh.size() > DirectLinkQueue.MAX_SIZE) {
            return "队列已满（上限 " + DirectLinkQueue.MAX_SIZE + " 项）";
        }
        List<String> merged = new ArrayList<>(existing);
        merged.addAll(fresh);
        // 仅改列表不动播放；episodeIndex 不变（追加只在尾部）
        screen.setEpisodeData(DirectLinkQueue.buildRoadJson(merged));
        int skipped = urls.size() - fresh.size();
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, sid,
            "将 " + fresh.size() + " 个直链加入队列（第 " + (existing.size() + 1) + " 位起）");
        KazumiMessages.sendSuccess(sp, "已加入队列（第 " + (existing.size() + 1) + " 位，共 "
            + merged.size() + " 项）" + (skipped > 0 ? "，跳过 " + skipped + " 条重复链接" : ""));
        KazumiLog.network.info("Queue add at {}: {} item(s), total {}", screenPos.toShortString(),
            fresh.size(), merged.size());
        return null;
    }

    /** 立即切播队列第 index 项（1-based），已播过的项可重播 */
    public static String jump(ServerPlayer sp, BlockPos screenPos, int index) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return "目标方块不是屏幕";
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null) return "该屏幕没有直链队列";
        String err = checkIndex(urls, index);
        if (err != null) return err;
        String label = labelAt(urls, index);
        playItemAt(sp, screenPos, screen, urls, index, "切播了队列项 " + label);
        KazumiMessages.sendSuccess(sp, "正在播放: " + label);
        return null;
    }

    /** 插队：把第 index 项移到当前项之后（下一个就播它）；当前项本身不可操作 */
    public static String moveAfterCurrent(ServerPlayer sp, BlockPos screenPos, int index) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return "目标方块不是屏幕";
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null) return "该屏幕没有直链队列";
        String err = checkIndex(urls, index);
        if (err != null) return err;
        int cur = screen.getEpisodeIndex();
        if (index == cur) return "该项正在播放";
        List<String> reordered = new ArrayList<>(urls);
        String url = reordered.remove(index - 1);
        // 移动点在当前项之前时，删除与插入的偏移恰好抵消，当前项序号保持不变
        reordered.add(cur, url);
        screen.setEpisodeData(DirectLinkQueue.buildRoadJson(reordered));
        String label = labelAt(reordered, Math.min(cur + 1, reordered.size()));
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, screen.getScreenId(),
            "将 " + label + " 移到下一个播放");
        KazumiMessages.sendSuccess(sp, "已将 " + label + " 移到下一个播放");
        return null;
    }

    /** 从队列移除第 index 项；当前播放项不可移除。已播项被移除时自动修正当前序号 */
    public static String remove(ServerPlayer sp, BlockPos screenPos, int index) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return "目标方块不是屏幕";
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null) return "该屏幕没有直链队列";
        String err = checkIndex(urls, index);
        if (err != null) return err;
        int cur = screen.getEpisodeIndex();
        if (index == cur) return "该项正在播放，请先切换后再移除";
        List<String> remaining = new ArrayList<>(urls);
        String label = labelAt(remaining, index);
        remaining.remove(index - 1);
        if (index < cur) {
            // 当前项序号随删除左移，保持指向同一个 URL
            screen.setEpisodeIndex(cur - 1);
        }
        screen.setEpisodeData(DirectLinkQueue.buildRoadJson(remaining));
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, screen.getScreenId(),
            "从队列移除了 " + label);
        KazumiMessages.sendSuccess(sp, "已移除: " + label);
        return null;
    }

    // ---- 内部 ----

    /** 以队列模式起播第 index 项：建组 + 写完整 NBT + 同步观看者 + 即时广播 + 通知 */
    private static void playItemAt(ServerPlayer sp, BlockPos screenPos, VideoScreenBlockEntity screen,
            List<String> urls, int index, String notifyText) {
        String url = urls.get(index - 1);
        UUID sid = screen.getScreenId();
        SyncGroupManager.get().onPlayStart(sp, sid, screenPos, url);
        screen.setPlaybackFull(url, 0, 0, index, DirectLinkQueue.buildRoadJson(urls));
        screen.setPlayingTitle(""); // 直链队列：清空番剧名，GUI 不显示"正在播放"行
        var g = SyncGroupManager.get().getGroup(sid);
        if (g != null) screen.setWatchingPlayers(g.watchingPlayersString());
        SyncGroupManager.get().broadcastSyncState(sid, sp.level().getServer());
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, sid, notifyText);
    }

    private static String checkIndex(List<String> urls, int index) {
        if (index < 1 || index > urls.size()) return "序号超出范围 (1-" + urls.size() + ")";
        return null;
    }

    private static String labelAt(List<String> urls, int index) {
        return DirectLinkQueue.makeLabel(urls.get(index - 1), index);
    }
}
