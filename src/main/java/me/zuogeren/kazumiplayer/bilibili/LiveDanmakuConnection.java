package me.zuogeren.kazumiplayer.bilibili;

import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 直播间弹幕长连接：getDanmuInfo 取 token 与节点（走 BilibiliApi 的 WBI 签名实现，整条链路匿名）→
 * wss /sub 认证（uid=0 与匿名 token 同源）→ 30s 心跳 → 解包后只回调 DANMU_MSG。
 * 长时间收不到弹幕不算失败（轮播/冷清房间正常），只有心跳无回音或连接断开才重连。
 * 掉线按 3s/6s/12s 退避重连（最多 3 次），每次重连重新取 token；连接稳定 60s 后重连预算复位。
 * 所有状态变更与发送都在同一条调度线程上串行执行，WebSocket 回调只做投递，避免并发状态竞争。
 */
final class LiveDanmakuConnection implements LiveDanmakuSession {

    private static final int HEARTBEAT_INTERVAL_MS = 30_000;
    private static final int HEARTBEAT_TIMEOUT_MS = 75_000;
    private static final int MAX_RECONNECTS = 3;
    private static final int[] RECONNECT_DELAY_MS = {3_000, 6_000, 12_000};
    private static final int STABLE_CONNECTION_MS = 60_000;

    private final long roomId;
    private final String cookie;
    private final LiveDanmakuListener listener;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private WebSocket socket;
    private ScheduledFuture<?> heartbeat;
    private int generation;
    private int attempts;
    private int hostIndex;
    private long connectedAtMs;
    private long lastHeartbeatReplyMs;
    private boolean connected;

    LiveDanmakuConnection(long roomId, String cookie, LiveDanmakuListener listener) {
        this.roomId = roomId;
        this.cookie = cookie;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Kazumi-BiliDanmaku-" + roomId);
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        scheduler.execute(this::connect);
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && connected;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        generation++;
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        WebSocket current = socket;
        socket = null;
        if (current != null) current.abort();
        scheduler.shutdown();
        KazumiLog.danmaku.debug("[bilibili] live danmaku session closed room={}", roomId);
    }

    private void connect() {
        if (closed.get()) return;
        KazumiLog.danmaku.debug("[bilibili] live danmaku connecting room={} attempt={}", roomId, attempts + 1);
        BilibiliApi.fetchLiveDanmakuAccess(roomId, cookie).whenComplete((access, error) -> {
            if (closed.get()) return;
            if (error != null) {
                scheduler.execute(() -> retry("获取弹幕接入信息失败：" + describe(error)));
                return;
            }
            scheduler.execute(() -> openSocket(access));
        });
    }

    private void openSocket(BilibiliApi.LiveDanmakuAccess access) {
        if (closed.get()) return;
        List<String> hosts = access.wssUrls();
        if (hosts.isEmpty()) {
            retry("弹幕接入节点为空");
            return;
        }
        String url = hosts.get(hostIndex % hosts.size());
        hostIndex++;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            retry("弹幕接入节点地址无效：" + url);
            return;
        }
        int gen = ++generation;
        KazumiLog.danmaku.debug("[bilibili] live danmaku subscribe room={} host={}", roomId, uri.getHost());
        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(uri, new SessionListener(gen, access))
            .whenComplete((webSocket, error) -> {
                if (error == null) return;
                scheduler.execute(() -> {
                    if (gen != generation) return;
                    retry("弹幕连接失败：" + describe(error));
                });
            });
    }

    private void onSocketOpen(int gen, WebSocket webSocket, BilibiliApi.LiveDanmakuAccess access) {
        if (closed.get() || gen != generation) {
            webSocket.abort();
            return;
        }
        socket = webSocket;
        connected = true;
        connectedAtMs = MonoClock.millis();
        lastHeartbeatReplyMs = connectedAtMs;
        webSocket.sendBinary(ByteBuffer.wrap(BilibiliLiveProtocol.encode(BilibiliLiveProtocol.OP_AUTH,
            BilibiliLiveProtocol.authBody(access.roomId(), access.token()))), true);
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = scheduler.scheduleAtFixedRate(this::heartbeatTick, HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void heartbeatTick() {
        if (closed.get()) return;
        WebSocket current = socket;
        if (current == null) return;
        if (MonoClock.millis() - lastHeartbeatReplyMs > HEARTBEAT_TIMEOUT_MS) {
            drop("心跳超时");
            return;
        }
        current.sendBinary(ByteBuffer.wrap(BilibiliLiveProtocol.encode(BilibiliLiveProtocol.OP_HEARTBEAT,
            BilibiliLiveProtocol.HEARTBEAT_BODY)), true).exceptionally(error -> {
                scheduler.execute(() -> drop("心跳发送失败：" + describe(error)));
                return null;
            });
    }

    /** 单帧解包后逐包处理：畸形包只丢自己那一条，不中断会话（解析异常一律记 DEBUG 并跳过） */
    private void onFrame(byte[] frame) {
        if (closed.get()) return;
        List<BilibiliLiveProtocol.Packet> packets;
        try {
            packets = BilibiliLiveProtocol.decode(frame);
        } catch (Throwable error) {
            KazumiLog.danmaku.debug("[bilibili] live frame decode failed: {}", describe(error));
            return;
        }
        for (BilibiliLiveProtocol.Packet packet : packets) {
            if (closed.get()) return;
            try {
                handlePacket(packet);
            } catch (Throwable error) {
                KazumiLog.danmaku.debug("[bilibili] live packet handling failed (op={}, cmd={}): {}",
                    packet.operation(), BilibiliLiveProtocol.commandOf(packet.payload()), describe(error));
            }
        }
    }

    private void handlePacket(BilibiliLiveProtocol.Packet packet) {
        switch (packet.operation()) {
            case BilibiliLiveProtocol.OP_AUTH_REPLY -> {
                int code = BilibiliLiveProtocol.parseAuthReply(packet.payload()).code();
                if (code == BilibiliLiveProtocol.AUTH_REPLY_UNPARSED) {
                    KazumiLog.danmaku.debug("[bilibili] live auth reply unreadable, waiting for heartbeat");
                } else if (code != 0) {
                    retry("弹幕认证失败：code " + code);
                }
            }
            case BilibiliLiveProtocol.OP_HEARTBEAT_REPLY -> lastHeartbeatReplyMs = MonoClock.millis();
            case BilibiliLiveProtocol.OP_MESSAGE -> {
                BilibiliDanmaku danmaku = BilibiliLiveProtocol.parseDanmaku(packet.payload());
                if (danmaku == null) return;
                try {
                    listener.onDanmaku(danmaku);
                } catch (Throwable error) {
                    KazumiLog.danmaku.debug("[bilibili] live danmaku callback failed: {}", describe(error));
                }
            }
            default -> {
            }
        }
    }

    private void onSocketClosed(int gen, String reason) {
        if (closed.get() || gen != generation) return;
        connected = false;
        WebSocket current = socket;
        socket = null;
        if (current != null) current.abort();
        retry(reason);
    }

    private void drop(String reason) {
        if (closed.get()) return;
        generation++;
        connected = false;
        WebSocket current = socket;
        socket = null;
        if (current != null) current.abort();
        retry(reason);
    }

    private void retry(String reason) {
        if (closed.get()) return;
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        if (connectedAtMs > 0 && MonoClock.millis() - connectedAtMs > STABLE_CONNECTION_MS) {
            attempts = 0;
        }
        connectedAtMs = 0;
        listener.onError(reason);
        if (attempts >= MAX_RECONNECTS) {
            KazumiLog.danmaku.debug("[bilibili] live danmaku gave up room={}: {}", roomId, reason);
            listener.onClosed(reason);
            close();
            return;
        }
        int delay = RECONNECT_DELAY_MS[Math.min(attempts, RECONNECT_DELAY_MS.length - 1)];
        attempts++;
        KazumiLog.danmaku.debug("[bilibili] live danmaku reconnect in {}ms ({}/{}) room={}",
            delay, attempts, MAX_RECONNECTS, roomId);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** WebSocket 回调：只做字节累积与线程投递，协议解析与状态变更都回到调度线程 */
    private final class SessionListener implements WebSocket.Listener {

        private final int gen;
        private final BilibiliApi.LiveDanmakuAccess access;

        SessionListener(int gen, BilibiliApi.LiveDanmakuAccess access) {
            this.gen = gen;
            this.access = access;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            scheduler.execute(() -> onSocketOpen(gen, webSocket, this.access));
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            byte[] frame = null;
            synchronized (pending) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                pending.write(chunk, 0, chunk.length);
                if (last) {
                    frame = pending.toByteArray();
                    pending.reset();
                }
            }
            if (frame != null) {
                byte[] complete = frame;
                scheduler.execute(() -> onFrame(complete));
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            scheduler.execute(() -> onSocketClosed(gen, "连接被关闭（" + statusCode + "）"));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            scheduler.execute(() -> onSocketClosed(gen, "连接异常：" + describe(error)));
        }
    }
}
