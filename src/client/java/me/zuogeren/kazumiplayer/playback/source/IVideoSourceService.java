package me.zuogeren.kazumiplayer.playback.source;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 视频源解析服务接口（对齐 Kazumi video_source_service.dart IVideoSourceService）。
 * Dart 的 Future/异常模型对应 Java 的 CompletableFuture/异常完成。
 */
public interface IVideoSourceService {

    /**
     * 解析视频源 URL。
     *
     * @param episodeUrl      集数页面 URL
     * @param useLegacyParser 是否使用旧版解析器（iframe 监听）
     * @param timeout         解析超时时间
     * @return 包含解析后的视频 URL 和元数据的 VideoSource
     *         可能异常完成：{@link VideoSourceResolveException.NotFound} /
     *         {@link VideoSourceResolveException.Timeout} / {@link VideoSourceResolveException.Cancelled}
     */
    CompletableFuture<VideoSource> resolve(String episodeUrl, boolean useLegacyParser, Duration timeout);

    /** 取消当前正在进行的解析（在途 resolve 将异常完成 Cancelled） */
    void cancel();

    /** 释放资源 */
    void dispose();
}
