package me.zuogeren.kazumiplayer.playback.source;

import me.zuogeren.kazumiplayer.util.KazumiLog;

import com.cinemamod.mcef.MCEF;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * WebView(MCEF) 视频源解析服务——对齐 Kazumi lib/services/video_source/webview_video_source_service.dart。
 *
 * 使用 MCEF 浏览器解析视频页面提取视频源 URL。
 * 浏览器实例在服务生命周期内复用，切换集数时调用 unloadPage 释放页面资源，
 * 仅在 {@link #dispose()} 时才真正销毁浏览器。
 *
 * 单个服务实例持有一个浏览器，因此解析任务按实例串行执行（resolveTail 链）；
 * 新请求会取消旧请求，旧请求经 {@link ResolveRequest#throwIfNotCurrent} 以 Cancelled 收尾。
 */
public class McefVideoSourceService implements IVideoSourceService {

    private McefSniffBrowser browser;
    private CompletableFuture<Void> resolveTail = CompletableFuture.completedFuture(null);
    private ResolveRequest activeRequest;

    private volatile Consumer<String> logListener;

    /** 订阅解析日志流（对齐 Kazumi onLog） */
    public void setOnLog(Consumer<String> listener) {
        this.logListener = listener;
        McefSniffBrowser b = browser;
        if (b != null) b.setOnLog(listener);
    }

    @Override
    public CompletableFuture<VideoSource> resolve(String episodeUrl, boolean useLegacyParser, Duration timeout) {
        CompletableFuture<Void> tail = resolveTail;
        if (tail == null) {
            // 服务已 dispose
            return CompletableFuture.failedFuture(new VideoSourceResolveException.Cancelled());
        }
        KazumiLog.sniff.info("[source] resolve request: {} (legacyParser={}, timeout={}s)",
            episodeUrl, useLegacyParser, timeout.toSeconds());

        ResolveRequest previous = activeRequest;
        if (previous != null) {
            KazumiLog.sniff.info("[source] superseding in-flight resolve");
            previous.cancel();
        }
        ResolveRequest request = new ResolveRequest();
        activeRequest = request;

        CompletableFuture<VideoSource> resolveFuture = tail
            .handle((v, t) -> null)
            .thenCompose(nil -> runResolve(request, episodeUrl, useLegacyParser, timeout));

        resolveTail = resolveFuture.handle((v, t) -> null);
        return resolveFuture;
    }

    private CompletableFuture<VideoSource> runResolve(ResolveRequest request, String episodeUrl,
            boolean useLegacyParser, Duration timeout) {
        // 浏览器操作约定在渲染线程执行；等待阶段留在回调线程
        CompletableFuture<VideoSource> started = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                request.throwIfNotCurrent(activeRequest);
                if (!MCEF.isInitialized()) {
                    throw new VideoSourceResolveException.NotFound("MCEF 未初始化，无法进行浏览器嗅探");
                }
                if (browser == null) {
                    browser = new McefSniffBrowser();
                    browser.setOnLog(log -> {
                        Consumer<String> l = logListener;
                        if (l != null) l.accept(log);
                    });
                }
                browser.loadUrl(episodeUrl, useLegacyParser);
                started.complete(null);
            } catch (Throwable t) {
                started.completeExceptionally(t);
            }
        });

        return started.thenCompose(nil -> {
            request.throwIfNotCurrent(activeRequest);

            CompletableFuture<VideoSource> parserFuture = browser.nextVideoParsed()
                .thenApply(event -> VideoSource.online(event.url(), event.format()))
                .orTimeout(timeout.toSeconds(), TimeUnit.SECONDS)
                .handle((source, t) -> {
                    if (t == null) return source;
                    Throwable cause = unwrap(t);
                    if (cause instanceof TimeoutException) {
                        // 对齐 Kazumi onTimeout：先确认未被接管再抛超时
                        request.throwIfNotCurrent(activeRequest);
                        throw new VideoSourceResolveException.Timeout(timeout);
                    }
                    if (cause instanceof VideoSourceResolveException rse) throw rse;
                    if (cause instanceof RuntimeException re) throw re;
                    throw new CompletionException(cause);
                });

            // 取消传播：请求被取消/接管时立刻以 Cancelled 完成
            CompletableFuture<VideoSource> cancelFuture = request.cancelledFuture()
                .thenApply(v -> {
                    throw new VideoSourceResolveException.Cancelled();
                });

            return parserFuture.applyToEither(cancelFuture, Function.identity())
                .whenComplete((v, t) -> {
                    if (activeRequest == request) {
                        activeRequest = null;
                    }
                    // 对齐蓝本 finally：无论成败都卸载页面释放资源
                    Minecraft.getInstance().execute(() -> {
                        McefSniffBrowser b = browser;
                        if (b != null) b.unloadPage();
                    });
                });
        });
    }

    @Override
    public void cancel() {
        ResolveRequest request = activeRequest;
        if (request != null) {
            request.cancel();
        }
    }

    @Override
    public void dispose() {
        KazumiLog.sniff.debug("[source] disposing video source service");
        resolveTail = null;
        cancel();
        activeRequest = null;
        Minecraft.getInstance().execute(() -> {
            if (browser != null) {
                browser.dispose();
                browser = null;
            }
        });
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException || t instanceof ExecutionException) {
            if (t.getCause() == null) break;
            t = t.getCause();
        }
        return t;
    }
}
