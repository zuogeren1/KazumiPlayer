package me.zuogeren.kazumiplayer.playback.source;

import java.util.concurrent.CompletableFuture;

/**
 * 解析请求身份与取消令牌（对齐 Kazumi webview_video_source_service.dart 的 _ResolveRequest）。
 * 新请求接管时取消旧请求；旧请求在每个关键节点通过 {@link #throwIfNotCurrent}
 * 感知"已取消/已不是当前请求"并以 Cancelled 异常收尾。
 */
final class ResolveRequest {

    private final CompletableFuture<Void> cancelled = new CompletableFuture<>();

    CompletableFuture<Void> cancelledFuture() {
        return cancelled;
    }

    boolean isCancelled() {
        return cancelled.isDone();
    }

    void cancel() {
        cancelled.complete(null);
    }

    /** 已取消或已被更新的请求接管时抛出 Cancelled */
    void throwIfNotCurrent(ResolveRequest current) {
        if (isCancelled() || current != this) {
            throw new VideoSourceResolveException.Cancelled();
        }
    }
}
