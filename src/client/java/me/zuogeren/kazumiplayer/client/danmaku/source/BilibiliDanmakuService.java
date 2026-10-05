package me.zuogeren.kazumiplayer.client.danmaku.source;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.bilibili.BilibiliApi;
import me.zuogeren.kazumiplayer.bilibili.BilibiliDanmaku;
import me.zuogeren.kazumiplayer.bilibili.LiveDanmakuListener;
import me.zuogeren.kazumiplayer.bilibili.LiveDanmakuSession;
import me.zuogeren.kazumiplayer.client.BilibiliCredentials;
import me.zuogeren.kazumiplayer.client.KazumiClientMessages;
import me.zuogeren.kazumiplayer.client.danmaku.ClientDanmakuStore;
import me.zuogeren.kazumiplayer.client.danmaku.DanmakuEntry;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B 站弹幕数据接入层（客户端唯一入口）：按屏幕装载片内时间轴弹幕或直播间实时弹幕，
 * 全部条目经 {@link ClientDanmakuStore} 单入口入队，随播放生命周期起停。
 *
 * <p>生命周期约束：
 * <ul>
 *   <li><b>幂等</b>：同一屏同一 cid / 同一房间重复 attach 直接返回（不发起第二次请求，不重建连接）；</li>
 *   <li><b>主线程</b>：所有 HTTP/WS 回调经 {@link Minecraft#execute} 切主线程后才触碰 Store 与渲染状态；</li>
 *   <li><b>迟到结果丢弃</b>：在途请求带代次号，装载前校验代次与 attach 身份（更换 cid/房间、detach
 *       均使代次或身份失配），旧请求的回调既不装载也不上报。</li>
 * </ul>
 *
 * <p>提醒策略（避免聊天刷屏）：只有"用户主动操作可感知"的装载失败才给一条聊天提示
 * （清晰度切换等手动动作，{@code notifyUser=true}）；自动起播/换集的加载失败只记 DEBUG 日志，
 * 用户可从清晰度切换重新触发一次解析。
 */
public final class BilibiliDanmakuService {

    private static final BilibiliDanmakuService INSTANCE = new BilibiliDanmakuService();

    /** 在途/已建立的弹幕源附件：视频按 cid 幂等，直播按房间幂等 */
    private sealed interface Attachment permits VideoAttachment, LiveAttachment {}

    /** 片内时间轴弹幕登记：stage 表示装载进度，fetchId 为在途请求代次 */
    private record VideoAttachment(long cid, long fetchId, int stage) implements Attachment {}

    /** 直播间弹幕登记：session 关闭即断开 WS */
    private record LiveAttachment(long roomId, LiveDanmakuSession session) implements Attachment {}

    private static final int STAGE_LOADING = 0;
    private static final int STAGE_LOADED = 1;
    private static final int STAGE_FAILED = 2;

    private final Map<BlockPos, Attachment> attachments = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong fetchSeq =
        new java.util.concurrent.atomic.AtomicLong();

    private BilibiliDanmakuService() {}

    public static BilibiliDanmakuService getInstance() {
        return INSTANCE;
    }

    /**
     * 装载视频片内弹幕（B 站视频屏）。
     *
     * @param cid        视频分 P 的 cid；≤0 直接返回
     * @param notifyUser 是否为用户主动操作触发的装载（失败时给一条聊天提示）
     */
    public void attachVideo(BlockPos pos, long cid, boolean notifyUser) {
        if (pos == null || cid <= 0) return;
        if (!config().danmakuBilibiliVideo.get()) {
            KazumiLog.danmaku.debug("Video danmaku disabled by config, skip attach at {}", pos);
            return;
        }
        Attachment current = attachments.get(pos);
        if (current instanceof VideoAttachment video && video.cid() == cid) {
            KazumiLog.danmaku.debug("Video danmaku already attached at {} (cid={}, stage={}), skip",
                pos, cid, video.stage());
            return;
        }
        detach(pos);
        long fetchId = fetchSeq.incrementAndGet();
        attachments.put(pos, new VideoAttachment(cid, fetchId, STAGE_LOADING));
        String cookie = BilibiliCredentials.get();
        // 凭据状态只记匿名/已登录（匿名只能拿高权重子集），不打印凭据值
        KazumiLog.danmaku.debug("Video danmaku attach at {} (cid={}, credentials={})",
            pos, cid, cookie.isEmpty() ? "anonymous" : "logged-in");

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            KazumiLog.danmaku.debug("Video danmaku attach skipped at {}: no level", pos);
            markFailedIfCurrent(pos, fetchId);
            return;
        }
        java.util.concurrent.CompletableFuture<java.util.List<BilibiliDanmaku>> future =
            BilibiliApi.fetchVideoDanmaku(cid, cookie);
        if (future == null) {
            KazumiLog.danmaku.debug("Video danmaku fetch unavailable at {} (cid={})", pos, cid);
            markFailedIfCurrent(pos, fetchId);
            if (notifyUser) notifyFailure(mc, "kazumiplayer.msg.danmaku_video_failed");
            return;
        }
        future.thenAccept(items -> mc.execute(() -> loadVideo(pos, cid, fetchId, items, notifyUser)))
            .exceptionally(t -> {
                mc.execute(() -> failVideo(pos, cid, fetchId, t, notifyUser));
                return null;
            });
    }

    /**
     * 建立直播间弹幕连接（直播屏）。
     *
     * @param notifyUser 是否为用户主动操作触发的连接（失败时给一条聊天提示）
     */
    public void attachLive(BlockPos pos, long roomId, boolean notifyUser) {
        if (pos == null || roomId <= 0) return;
        if (!config().danmakuBilibiliLive.get()) {
            KazumiLog.danmaku.debug("Live danmaku disabled by config, skip attach at {}", pos);
            return;
        }
        Attachment current = attachments.get(pos);
        if (current instanceof LiveAttachment live && live.roomId() == roomId) {
            KazumiLog.danmaku.debug("Live danmaku already attached at {} (room={}), skip", pos, roomId);
            return;
        }
        detach(pos);
        String cookie = BilibiliCredentials.get();
        Minecraft mc = Minecraft.getInstance();
        LiveDanmakuListener listener = new LiveDanmakuListener() {
            @Override
            public void onDanmaku(BilibiliDanmaku item) {
                mc.execute(() -> enqueueLive(pos, roomId, item));
            }

            @Override
            public void onError(String message) {
                mc.execute(() -> KazumiLog.danmaku.debug(
                    "Live danmaku error at {} (room={}): {}", pos, roomId, message));
            }

            @Override
            public void onClosed(String reason) {
                KazumiLog.danmaku.debug("Live danmaku closed at {} (room={}): {}", pos, roomId, reason);
            }
        };
        LiveDanmakuSession session;
        try {
            session = BilibiliApi.openLiveDanmaku(roomId, cookie, listener);
        } catch (RuntimeException e) {
            KazumiLog.danmaku.debug("Live danmaku connect failed at {} (room={}): {}",
                pos, roomId, e.toString());
            if (notifyUser) notifyFailure(mc, "kazumiplayer.msg.danmaku_live_failed");
            return;
        }
        attachments.put(pos, new LiveAttachment(roomId, session));
        KazumiLog.danmaku.debug("Live danmaku attach at {} (room={}, open={}, credentials={})",
            pos, roomId, session.isOpen(), cookie.isEmpty() ? "anonymous" : "logged-in");
    }

    /** 关闭该屏的弹幕源（在途请求作废、WS 断开）。幂等。 */
    public void detach(BlockPos pos) {
        if (pos == null) return;
        Attachment removed = attachments.remove(pos);
        if (removed == null) return;
        if (removed instanceof LiveAttachment live && live.session() != null) {
            try {
                live.session().close();
            } catch (RuntimeException e) {
                KazumiLog.danmaku.debug("Live danmaku close failed at {}: {}", pos, e.toString());
            }
        }
        KazumiLog.danmaku.debug("Danmaku detach at {} (type={})", pos, removed.getClass().getSimpleName());
    }

    /** 全量断开（断线/退出存档）。幂等。 */
    public void detachAll() {
        if (attachments.isEmpty()) return;
        int count = attachments.size();
        for (BlockPos pos : new ArrayList<>(attachments.keySet())) {
            detach(pos);
        }
        KazumiLog.danmaku.debug("Danmaku detachAll: {} attachment(s) closed", count);
    }

    /** 该屏当前装载的 cid（未装载片内弹幕时返回 0）；审计与验证用 */
    public long attachedCid(BlockPos pos) {
        return attachments.get(pos) instanceof VideoAttachment video ? video.cid() : 0L;
    }

    /** 该屏当前连接的直播间号（未连接实时弹幕时返回 0）；审计与验证用 */
    public long attachedRoomId(BlockPos pos) {
        return attachments.get(pos) instanceof LiveAttachment live ? live.roomId() : 0L;
    }

    // ---- 内部：装载与失效 ----

    /** 装载片内弹幕（主线程）：仅在代次仍是当前请求时生效，旧请求结果静默丢弃 */
    private void loadVideo(BlockPos pos, long cid, long fetchId, List<BilibiliDanmaku> items,
                           boolean notifyUser) {
        Attachment current = attachments.get(pos);
        if (!(current instanceof VideoAttachment video) || video.fetchId() != fetchId) {
            KazumiLog.danmaku.debug("Stale video danmaku discarded at {} (cid={}, request={})",
                pos, cid, fetchId);
            return;
        }
        if (items == null || items.isEmpty()) {
            attachments.put(pos, new VideoAttachment(cid, fetchId, STAGE_LOADED));
            KazumiLog.danmaku.debug("Video danmaku load returned no entries at {} (cid={})", pos, cid);
            return;
        }
        long offsetMs = config().danmakuTimeOffsetMs.get();
        int maxEntries = config().danmakuMaxEntries.get();
        List<DanmakuEntry> entries = capEntries(buildEntries(items, offsetMs), maxEntries);
        attachments.put(pos, new VideoAttachment(cid, fetchId, STAGE_LOADED));
        ClientDanmakuStore.clear(pos);
        ClientDanmakuStore.enqueueAll(pos, entries);
        KazumiLog.danmaku.debug("Video danmaku loaded at {}: {} entries (cid={}, offset={}ms)",
            pos, entries.size(), cid, offsetMs);
    }

    /** 单次装载上限：取时间轴靠前的一段（弹幕按 timeMs 升序），超出部分丢弃并记 DEBUG */
    private static List<DanmakuEntry> capEntries(List<DanmakuEntry> entries, int maxEntries) {
        if (maxEntries <= 0 || entries.size() <= maxEntries) return entries;
        KazumiLog.danmaku.debug("Video danmaku capped: {} -> {} entries", entries.size(), maxEntries);
        return new ArrayList<>(entries.subList(0, maxEntries));
    }

    /** 拉取结果的条目转换与清洗（网络回调线程执行，不触碰 Store 与 MC 状态） */
    private static List<DanmakuEntry> buildEntries(List<BilibiliDanmaku> items, long offsetMs) {
        List<DanmakuEntry> entries = new ArrayList<>(items.size());
        for (BilibiliDanmaku item : items) {
            if (item == null || item.text() == null || item.text().isBlank()) continue;
            entries.add(DanmakuEntry.videoTimeline(item.text(), item.mode(), item.colorRgb(),
                item.fontSizePercent(), item.timeMs() + offsetMs));
        }
        return entries;
    }

    /** 装载失败（主线程）：请求已失效时静默丢弃，仍在途则标记失败并按需提示 */
    private void failVideo(BlockPos pos, long cid, long fetchId, Throwable t, boolean notifyUser) {
        if (!markFailedIfCurrent(pos, fetchId)) {
            KazumiLog.danmaku.debug("Stale video danmaku failure discarded at {} (cid={}, request={})",
                pos, cid, fetchId);
            return;
        }
        KazumiLog.danmaku.debug("Video danmaku load failed at {} (cid={}): {}",
            pos, cid, unwrapMessage(t));
        if (notifyUser) notifyFailure(Minecraft.getInstance(), "kazumiplayer.msg.danmaku_video_failed");
    }

    /** 直播条目入队（主线程）：连接已被替换/断开时丢弃 */
    private void enqueueLive(BlockPos pos, long roomId, BilibiliDanmaku item) {
        if (item == null || item.text() == null || item.text().isBlank()) return;
        if (!(attachments.get(pos) instanceof LiveAttachment live) || live.roomId() != roomId) {
            KazumiLog.danmaku.debug("Stale live danmaku discarded at {} (room={})", pos, roomId);
            return;
        }
        ClientDanmakuStore.enqueue(pos, DanmakuEntry.live(item.text(), item.mode(), item.colorRgb(),
            item.fontSizePercent()));
    }

    /** 请求仍为当前代次时把登记标记为失败（已 detach 或已换源时返回 false） */
    private boolean markFailedIfCurrent(BlockPos pos, long fetchId) {
        Attachment current = attachments.get(pos);
        if (!(current instanceof VideoAttachment video) || video.fetchId() != fetchId) return false;
        attachments.put(pos, new VideoAttachment(video.cid(), fetchId, STAGE_FAILED));
        return true;
    }

    private static void notifyFailure(Minecraft mc, String langKey) {
        if (mc.level == null) return;
        KazumiClientMessages.chatWarn(Component.translatable(langKey).getString());
    }

    private static ClientConfig config() {
        return ClientConfig.CONFIG;
    }

    private static String unwrapMessage(Throwable t) {
        Throwable cause = t;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
