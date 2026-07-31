package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import me.zuogeren.kazumiplayer.ClientConfig;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefCallback;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * MCEF 浏览器视频嗅探器
 * 加载剧集播放页 → 注入 JS → 提取 m3u8/mp4 视频直链
 *
 * JS 嗅探脚本移植自 Kazumi Dart webview/video/impl/video_webview_impl.dart
 */
public class VideoSniffer {

    // 视频 URL 嗅探报告前缀
    private static final String SNIFF_PREFIX = "KAZUMI_VIDEO_URL:";

    // 注入的嗅探 JS
    private static final String SNIFF_SCRIPT = """
        (function() {
            if (window.__kazumi_sniffed) return;
            window.__kazumi_sniffed = true;
            const report = function(url) {
                if (url && (url.startsWith('http') || url.startsWith('//'))) {
                    if (url.startsWith('//')) url = 'https:' + url;
                    // 排除网页播放页/iframe 页面（video 直链不应是 .html/.php）
                    if (/\\.(html?|php)([?#]|$)/i.test(url)) return;
                    console.log('KAZUMI_VIDEO_URL:' + url);
                }
            };

            // 1. Hook fetch/Response 拦截 m3u8
            const _fetch = window.fetch;
            window.fetch = function(...args) {
                return _fetch.apply(this, args).then(r => {
                    const clone = r.clone();
                    clone.text().then(t => { if (t.trim().startsWith('#EXTM3U')) report(clone.url); }).catch(()=>{});
                    return r;
                });
            };

            // 2. Hook XHR 拦截 m3u8
            const _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, url) {
                this.addEventListener('load', () => {
                    try { if (this.responseText.trim().startsWith('#EXTM3U')) report(url); } catch(e) {}
                });
                return _open.apply(this, arguments);
            };

            // 3. 扫描已有的 video 元素
            document.querySelectorAll('video').forEach(v => {
                if (v.src) report(v.src);
                v.querySelectorAll('source').forEach(s => { if (s.src) report(s.src); });
            });

            // 4. MutationObserver 监听新 video 元素
            new MutationObserver(muts => {
                for (const m of muts) {
                    for (const n of m.addedNodes) {
                        if (n.nodeName === 'VIDEO' && n.src) report(n.src);
                        if (n.querySelectorAll) {
                            n.querySelectorAll('video').forEach(v => {
                                if (v.src) report(v.src);
                            });
                        }
                    }
                    if (m.type === 'attributes' && m.target.nodeName === 'VIDEO' && m.target.src) {
                        report(m.target.src);
                    }
                }
            }).observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['src']});

            // 5. 定时轮询兜底
            let polls = 0;
            const timer = setInterval(() => {
                polls++;
                document.querySelectorAll('video').forEach(v => {
                    if (v.src && !v.src.startsWith('blob:')) report(v.src);
                });
                document.querySelectorAll('iframe').forEach(f => {
                    try { if (f.src) report(f.src); } catch(e) {}
                });
                if (polls > 30) clearInterval(timer);
            }, 1000);
        })();
        """;

    /**
     * 嗅探视频直链
     * @param pageUrl 剧集播放页 URL
     * @return CompletableFuture<String> 视频直链 (m3u8/mp4)
     */
    public CompletableFuture<String> sniff(String pageUrl) {
        KazumiLog.sniff.debug("sniff start pageUrl={}", pageUrl);
        CompletableFuture<String> future = new CompletableFuture<>();
        int timeoutSec = ClientConfig.CONFIG.sniffTimeoutSeconds.get();

        try {
            if (!MCEF.isInitialized()) {
                future.completeExceptionally(new RuntimeException("MCEF 未初始化"));
                return future;
            }

            var mc = Minecraft.getInstance();
            MCEFBrowser browser = MCEF.createBrowser(pageUrl, true);

            // 原生网络层拦截（Kazumi shouldInterceptRequest 同款通用方案）：
            // 视频源可能嵌套在 iframe/第三方播放页中，JS 嗅探够不到时，
            // 只要浏览器发出 .m3u8 请求或带 Range 的视频流请求，就在这里报告。
            int browserId = browser.getIdentifier();
            CefRequestHandler requestHandler = new CefRequestHandler() {
                @Override
                public boolean onBeforeBrowse(CefBrowser b, CefFrame frame, CefRequest request,
                        boolean isRedirect, boolean isMainFrame) {
                    return false;
                }

                @Override
                public boolean onOpenURLFromTab(CefBrowser b, CefFrame frame, String url, boolean isUserGesture) {
                    return false;
                }

                @Override
                public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame frame,
                        CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
                        BoolRef disableDefaultHandling) {
                    return new CefResourceRequestHandlerAdapter() {
                        @Override
                        public boolean onBeforeResourceLoad(CefBrowser br, CefFrame fr, CefRequest req) {
                            if (br.getIdentifier() == browserId && isVideoRequest(req)) {
                                future.complete(req.getURL());
                            }
                            return false; // 不阻止请求，仅观察
                        }
                    };
                }

                @Override
                public boolean getAuthCredentials(CefBrowser b, String originUrl, boolean isProxy, String host,
                        int port, String realm, String scheme, CefAuthCallback callback) {
                    return false;
                }

                @Override
                public boolean onCertificateError(CefBrowser b, CefLoadHandler.ErrorCode errorCode,
                        String requestUrl, CefCallback callback) {
                    return false;
                }

                @Override
                public void onRenderProcessTerminated(CefBrowser b, TerminationStatus status) {
                }
            };
            MCEF.getClient().getHandle().addRequestHandler(requestHandler);

            var handler = new CefDisplayHandlerAdapter() {
                @Override
                public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level,
                        String message, String source, int line) {
                    if (message.startsWith(SNIFF_PREFIX)) {
                        String videoUrl = message.substring(SNIFF_PREFIX.length());
                        KazumiLog.sniff.info("Sniffed video URL: {}", videoUrl);
                        future.complete(videoUrl);
                        return true;
                    }
                    return false;
                }
            };
            MCEF.getClient().addDisplayHandler(handler);

            // 页面加载完成后注入嗅探脚本
            MCEF.getClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser b, org.cef.browser.CefFrame frame, int httpStatusCode) {
                    // 主页面与所有 iframe 都注入：解析站（如 7sefun→lmm35）的视频源
                    // 可能嵌套在第三方播放页 iframe 中，仅注入主页面会嗅探超时
                    frame.executeJavaScript(SNIFF_SCRIPT, pageUrl, 0);
                }
            });

            // 超时处理
            future.orTimeout(timeoutSec, TimeUnit.SECONDS)
                .exceptionally(e -> null);

            // 清理
            future.whenComplete((url, err) -> {
                MCEF.getClient().removeDisplayHandler(handler);
                MCEF.getClient().getHandle().removeRequestHandler();
                browser.close();
                KazumiLog.sniff.debug("sniff done url={} err={}, restoring mouse", url, err);
                // 浏览器创建/关闭可能抢走窗口焦点导致鼠标脱离准心，恢复鼠标捕获
                Minecraft.getInstance().execute(() ->
                    ClientDisconnectHandler.forceRestoreMouseGrab(Minecraft.getInstance()));
                if (err != null) {
                    KazumiLog.sniff.warn("Sniff failed: {}", err.getMessage());
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * 判断请求是否为视频源（对齐 Kazumi shouldInterceptRequest）：
     * 1) .m3u8 结尾
     * 2) 带 Range: bytes= 且非静态资源（视频流请求）
     */
    private static boolean isVideoRequest(CefRequest request) {
        String url = request.getURL();
        if (url == null) return false;
        String lower = url.toLowerCase();

        // 广告请求忽略
        if (lower.contains("googleads") || lower.contains("googlesyndication")
                || lower.contains("adtrafficquality") || lower.contains("doubleclick")) {
            return false;
        }

        if (lower.endsWith(".m3u8")) {
            return true;
        }

        Map<String, String> headers = new HashMap<>();
        request.getHeaderMap(headers);
        if (headers.isEmpty()) return false;
        String range = headers.get("Range");
        if (range == null || !range.startsWith("bytes=")) return false;
        // 排除静态资源（Range 请求但非视频）
        return !(lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".html")
                || lower.endsWith(".json") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".svg")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".wasm"));
    }
}
