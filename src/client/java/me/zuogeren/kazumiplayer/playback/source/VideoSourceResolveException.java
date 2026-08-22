package me.zuogeren.kazumiplayer.playback.source;

import java.time.Duration;

/**
 * 视频源解析异常体系（对齐 Kazumi video_source_service.dart 的
 * VideoSourceNotFoundException / VideoSourceTimeoutException / VideoSourceCancelledException）
 */
public abstract class VideoSourceResolveException extends RuntimeException {

    protected VideoSourceResolveException(String message) {
        super(message);
    }

    /** 视频源未找到 */
    public static final class NotFound extends VideoSourceResolveException {
        public NotFound() {
            super("Video source not found");
        }

        public NotFound(String message) {
            super(message);
        }
    }

    /** 解析超时 */
    public static final class Timeout extends VideoSourceResolveException {
        public Timeout(Duration timeout) {
            super("Timed out after " + timeout.toSeconds() + "s");
        }
    }

    /** 解析被取消或被新请求接管（上层应静默收尾，不算失败） */
    public static final class Cancelled extends VideoSourceResolveException {
        public Cancelled() {
            super("Resolution was cancelled");
        }
    }
}
