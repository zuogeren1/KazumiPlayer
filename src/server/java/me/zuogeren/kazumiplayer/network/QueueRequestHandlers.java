package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import me.zuogeren.kazumiplayer.util.SyncNotificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

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
    public static GuiPayloads.ErrorPayload submit(ServerPlayer sp, BlockPos screenPos, List<String> rawUrls) {
        List<String> urls = new ArrayList<>();
        for (String u : rawUrls) {
            String t = u == null ? "" : u.trim();
            if (!t.isEmpty() && t.length() <= 2048) urls.add(t);
        }
        if (urls.isEmpty()) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.empty_url");

        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.not_screen");
        UUID sid = screen.getScreenId();

        // 空闲（含任何残留数据）：直接以本次提交起播
        if (screen.getEpisodeUrl().isEmpty()) {
            playItemAt(sp, screenPos, screen, urls, 1, Component.translatable("kazumiplayer.msg.notify.queue_start"));
            KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.play_started", urls.get(0));
            KazumiLog.network.info("Queue start at {}: {} item(s)", screenPos.toShortString(), urls.size());
            return null;
        }

        List<String> existing = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (existing == null) {
            return GuiPayloads.ErrorPayload.of("kazumiplayer.err.series_blocking_queue");
        }
        // 重复检测：已在队列中的链接跳过；全部重复则直接提示
        List<String> fresh = new ArrayList<>(urls);
        fresh.removeAll(existing);
        if (fresh.isEmpty()) {
            return GuiPayloads.ErrorPayload.of("kazumiplayer.err.already_queued");
        }
        if (existing.size() + fresh.size() > DirectLinkQueue.MAX_SIZE) {
            return GuiPayloads.ErrorPayload.of("kazumiplayer.err.queue_full", String.valueOf(DirectLinkQueue.MAX_SIZE));
        }
        List<String> merged = new ArrayList<>(existing);
        merged.addAll(fresh);
        // 仅改列表不动播放；episodeIndex 不变（追加只在尾部）
        screen.setEpisodeData(DirectLinkQueue.buildRoadJson(merged));
        int skipped = urls.size() - fresh.size();
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, sid,
            Component.translatable("kazumiplayer.msg.notify.queue_added", fresh.size(), existing.size() + 1));
        KazumiMessages.sendSuccessKey(sp, skipped > 0
                ? "kazumiplayer.msg.queue_added_skipped" : "kazumiplayer.msg.queue_added",
            String.valueOf(existing.size() + 1), String.valueOf(merged.size()), String.valueOf(skipped));
        KazumiLog.network.info("Queue add at {}: {} item(s), total {}", screenPos.toShortString(),
            fresh.size(), merged.size());
        return null;
    }

    /** 立即切播队列第 index 项（1-based），已播过的项可重播 */
    public static GuiPayloads.ErrorPayload jump(ServerPlayer sp, BlockPos screenPos, int index) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.not_screen");
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.no_queue");
        var err = checkIndex(urls, index);
        if (err != null) return err;
        String label = labelAt(urls, index);
        playItemAt(sp, screenPos, screen, urls, index, Component.translatable("kazumiplayer.msg.notify.jumped", label));
        KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.now_playing_label", label);
        return null;
    }

    /** 插队：把第 index 项移到当前项之后（下一个就播它）；当前项本身不可操作 */
    public static GuiPayloads.ErrorPayload moveAfterCurrent(ServerPlayer sp, BlockPos screenPos, int index) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.not_screen");
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.no_queue");
        var err = checkIndex(urls, index);
        if (err != null) return err;
        int cur = screen.getEpisodeIndex();
        if (index == cur) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.item_playing");
        List<String> reordered = new ArrayList<>(urls);
        String url = reordered.remove(index - 1);
        // 移动点在当前项之前时，删除与插入的偏移恰好抵消，当前项序号保持不变
        reordered.add(cur, url);
        screen.setEpisodeData(DirectLinkQueue.buildRoadJson(reordered));
        String label = labelAt(reordered, Math.min(cur + 1, reordered.size()));
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, screen.getScreenId(),
            Component.translatable("kazumiplayer.msg.notify.moved", label));
        KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.moved", label);
        return null;
    }

    /** 从队列移除第 index 项；当前播放项不可移除。已播项被移除时自动修正当前序号 */
    public static GuiPayloads.ErrorPayload remove(ServerPlayer sp, BlockPos screenPos, int index) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.not_screen");
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.no_queue");
        var err = checkIndex(urls, index);
        if (err != null) return err;
        int cur = screen.getEpisodeIndex();
        if (index == cur) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.item_playing_remove");
        List<String> remaining = new ArrayList<>(urls);
        String label = labelAt(remaining, index);
        remaining.remove(index - 1);
        if (index < cur) {
            // 当前项序号随删除左移，保持指向同一个 URL
            screen.setEpisodeIndex(cur - 1);
        }
        screen.setEpisodeData(DirectLinkQueue.buildRoadJson(remaining));
        SyncNotificationUtil.notifyOtherWatchers(sp, screenPos, screen.getScreenId(),
            Component.translatable("kazumiplayer.msg.notify.removed", label));
        KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.removed", label);
        return null;
    }

    /**
     * 播放失败自动跳过（客户端 WaterMediaPlayer 失败回调触发，非手动操作）：
     * <ul>
     *   <li>队列模式 → 移除正在播放项并自动切播下一个（原 cur+1 位置；末项取新末尾）；
     *       连续坏源会逐个跳过直至队列耗尽或遇到可播项</li>
     *   <li>唯一项被跳过或无队列上下文（单项直链/残留状态）→ 清空并停止本屏播放</li>
     * </ul>
     */
    public static GuiPayloads.ErrorPayload skipCurrent(ServerPlayer sp, BlockPos screenPos) {
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.not_screen");
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (screen.getEpisodeUrl().isEmpty() || urls == null || urls.isEmpty()) {
            clearAndStop(sp, screenPos, screen, Component.translatable("kazumiplayer.msg.notify.skipped_broken"));
            KazumiLog.network.info("Queue skip-current with no queue at {}, stopped", screenPos.toShortString());
            return null;
        }
        int cur = Math.max(1, Math.min(screen.getEpisodeIndex(), urls.size()));
        String label = labelAt(urls, cur);
        if (urls.size() == 1) {
            clearAndStop(sp, screenPos, screen, Component.translatable("kazumiplayer.msg.notify.skipped_named", label));
            KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.skipped_stopped", label);
            KazumiLog.network.info("Queue skip-current (last item) at {}", screenPos.toShortString());
            return null;
        }
        List<String> remaining = new ArrayList<>(urls);
        remaining.remove(cur - 1);
        int next = Math.min(cur, remaining.size()); // 原 cur+1 删除后仍在原下标；末项取新末尾
        playItemAt(sp, screenPos, screen, remaining, next,
            Component.translatable("kazumiplayer.msg.notify.skipped_advance", label));
        KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.skipped_now", label, labelAt(remaining, next));
        KazumiLog.network.info("Queue skip-current at {}: auto-advance to #{}",
            screenPos.toShortString(), next);
        return null;
    }

    /** 清空播放状态并停整屏：clear NBT + 清观看者 + 删组，其他观看者收 PlayStopPacket 即时停播与取消在途嗅探 */
    private static void clearAndStop(ServerPlayer sp, BlockPos screenPos,
            VideoScreenBlockEntity screen, net.minecraft.network.chat.Component reasonText) {
        UUID sid = screen.getScreenId();
        var g = SyncGroupManager.get().getGroup(sid);
        List<UUID> watchers = g != null ? List.copyOf(g.players) : List.of();

        screen.clearPlayback();
        screen.setWatchingPlayers("");
        SyncGroupManager.get().leaveByScreenId(sid);

        var server = sp.level().getServer();
        for (UUID pid : watchers) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null && p != sp) {
                PacketDistributor.sendToPlayer(p, new PlayStopPacket(screenPos));
                KazumiMessages.sendInfo(p, sp.getName().getString() + " " + reasonText);
            }
        }
    }

    // ---- 内部 ----

    /**
     * 立即切播一条直链（频道目录「切」按钮 / 通用换播）：
     * <ul>
     *   <li>屏幕空闲 → 单项起播（重置队列）</li>
     *   <li>队列播放中：该 URL 已在队列 → 直接 jump 到它；
     *       不在队列 → 插入当前项之后并立即切播（原队列顺序保留，本项播完自动继续原队列）</li>
     *   <li>规则剧集中 → 拒绝（与 queue_add 同语义）</li>
     * </ul>
     */
    public static GuiPayloads.ErrorPayload playNow(ServerPlayer sp, BlockPos screenPos, String url) {
        String t = url == null ? "" : url.trim();
        if (t.isEmpty() || t.length() > 2048) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.empty_url");
        var be = sp.level().getBlockEntity(screenPos);
        if (!(be instanceof VideoScreenBlockEntity screen)) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.not_screen");

        boolean idle = screen.getEpisodeUrl().isEmpty();
        List<String> existing = idle ? null : DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (!idle && existing == null) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.series_blocking_switch");

        // 空闲（含任何残留数据）：以该直链单项起播
        if (idle || existing.isEmpty()) {
            playItemAt(sp, screenPos, screen, List.of(t), 1, Component.translatable("kazumiplayer.msg.notify.play_start"));
            KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.now_playing_label", DirectLinkQueue.makeLabel(t, 1));
            return null;
        }

        int cur = Math.max(1, Math.min(screen.getEpisodeIndex(), existing.size()));
        int idx = existing.indexOf(t);
        if (idx >= 0) {
            // 已在队列中：当前项提示正在播；否则直接切到它
            if (idx + 1 == cur) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.channel_playing");
            return jump(sp, screenPos, idx + 1);
        }
        if (existing.size() >= DirectLinkQueue.MAX_SIZE) {
            return GuiPayloads.ErrorPayload.of("kazumiplayer.err.queue_full", String.valueOf(DirectLinkQueue.MAX_SIZE));
        }
        // 插入当前项之后并立即切播：target = cur+1 (1-based)
        List<String> merged = new ArrayList<>(existing);
        merged.add(cur, t);
        int target = cur + 1;
        String label = DirectLinkQueue.makeLabel(t, target);
        playItemAt(sp, screenPos, screen, merged, target, Component.translatable("kazumiplayer.msg.notify.switched", label));
        KazumiMessages.sendSuccessKey(sp, "kazumiplayer.msg.now_playing_label", label);
        KazumiLog.network.info("Queue play-now at {}: inserted after #{} -> #{}",
            screenPos.toShortString(), cur, target);
        return null;
    }

    /**
     * 以队列模式起播第 index 项：建组 + 写完整 NBT + 同步观看者 + 即时广播 + 通知。
     * 目标项之前的已播前缀随切换一并移出队列——列表自新当前项起重写、episodeIndex 归 1，
     * jump/playNow/skipCurrent 等所有队列内切换入口统一生效。
     */
    private static void playItemAt(ServerPlayer sp, BlockPos screenPos, VideoScreenBlockEntity screen,
            List<String> urls, int index, net.minecraft.network.chat.Component notifyText) {
        if (index > 1) {
            urls = new ArrayList<>(urls.subList(index - 1, urls.size()));
            index = 1;
        }
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

    private static GuiPayloads.ErrorPayload checkIndex(List<String> urls, int index) {
        if (index < 1 || index > urls.size()) return GuiPayloads.ErrorPayload.of("kazumiplayer.err.index_out_of_range", String.valueOf(urls.size()));
        return null;
    }

    private static String labelAt(List<String> urls, int index) {
        return DirectLinkQueue.makeLabel(urls.get(index - 1), index);
    }
}
