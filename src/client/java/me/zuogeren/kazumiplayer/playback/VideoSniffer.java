package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.client.BrowserCookieStore;
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

    // 页面 Cookie 收割前缀（payload 格式：host|document.cookie）
    private static final String COOKIE_PREFIX = "KAZUMI_COOKIES:";

    // MCEF 内嵌 CEF 的 Chromium 版本较旧，部分站点的 Cloudflare WAF 会按 UA 拦截
    // （同站点 java HttpClient 用新 Chrome UA 却能 200）。嗅探期间统一伪装成现代 Chrome，
    // HTTP 头与页面内 navigator.userAgent 同时覆盖，保持一致性。
    private static final String SPOOFED_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** 嗅探浏览器实际使用的伪装 UA——收割的 Cookie 与它绑定，HTTP 请求复用 Cookie 时必须成对使用 */
    public static String getSpoofedUa() {
        return SPOOFED_UA;
    }

    // 嗅探期间是否出现过 Cloudflare 挑战/拦截页特征资源（跨重试累积；
    // 用于失败时给出"站点反爬/不稳定"的针对性提示。新一轮播放由 PlaybackManager 重置）
    private static volatile boolean cfChallengeDetected;

    // ---- 常驻共享浏览器（Kazumi 同款实例复用策略）----
    // 浏览器只创建一次、嗅探间隙导航到 about:blank，Cookie（含 CF 的 cf_clearance）
    // 跨嗅探保留：挑战通过一次后，同站点后续集数/重试不再被拦。
    // handler 只注册一次，回调统一路由到 activeFuture。
    private static MCEFBrowser sharedBrowser;
    private static int sharedBrowserId = -1;
    private static volatile CompletableFuture<String> activeFuture;
    private static volatile String activePageUrl;

    // 早期注入的 fetch/XHR hook（onLoadStart）：页面加载过程中 fetch 到的 m3u8
    // （如解析站先 fetch 带签名的播放地址再设 video.src）在 onLoadEnd 之前就能捕获。
    // 独立防重标记，与完整脚本分开。
    private static final String SNIFF_HOOK_SCRIPT = """
        (function() {
            if (window.__kazumi_sniffed_hook) return;
            window.__kazumi_sniffed_hook = true;
            const report = function(url) {
                if (url && (url.startsWith('http') || url.startsWith('//'))) {
                    if (url.startsWith('//')) url = 'https:' + url;
                    if (/\\.(html?|php)([?#]|$)/i.test(url)) return;
                    console.log('KAZUMI_VIDEO_URL:' + url);
                }
            };
            // 与 HTTP 头伪装保持一致：页面/挑战 JS 读到的 UA 也必须是新版本
            try {
                Object.defineProperty(navigator, 'userAgent', {get: function() { return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'; }});
                Object.defineProperty(navigator, 'appVersion', {get: function() { return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'.replace('Mozilla/', ''); }});
            } catch(e) {}

            // Hook fetch/Response 拦截 m3u8
            const _fetch = window.fetch;
            window.fetch = function(...args) {
                return _fetch.apply(this, args).then(r => {
                    const clone = r.clone();
                    clone.text().then(t => { if (t.trim().startsWith('#EXTM3U')) report(clone.url); }).catch(()=>{});
                    return r;
                });
            };
            // Hook XHR 拦截 m3u8
            const _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, url) {
                this.addEventListener('load', () => {
                    try { if (this.responseText.trim().startsWith('#EXTM3U')) report(url); } catch(e) {}
                });
                return _open.apply(this, arguments);
            };
        })();
        """;

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

    /** 外部停止播放时取消在途嗅探（优雅释放，不触发重试） */
    public static void cancelActiveSniff() {
        CompletableFuture<String> f = activeFuture;
        if (f != null) {
            KazumiLog.sniff.debug("cancelling active sniff by external request");
            f.complete(null);
        }
    }

    /** 本轮播放流程中是否出现过 Cloudflare 挑战/拦截页特征 */
    public boolean isCfChallengeDetected() {
        return cfChallengeDetected;
    }

    /** 新一轮播放开始前重置检测状态 */
    public void resetCfChallengeDetection() {
        cfChallengeDetected = false;
    }

    /**
     * 嗅探视频直链（复用常驻浏览器；新任务接管式取代在途旧任务）
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

            // 接管式并发控制：停止后快速重播/换集时，上一轮嗅探可能仍在超时窗口内，
            // 新任务直接取代（旧等待方收到 null 静默收尾，不触发重试）
            CompletableFuture<String> previous = activeFuture;
            activeFuture = future;
            if (previous != null) {
                KazumiLog.sniff.debug("superseding in-flight sniff task");
                previous.complete(null);
            }

            // 首次创建时直接以目标页初始化（原生浏览器异步就绪，过早 loadURL 会丢失导航）
            boolean created = ensureBrowser(pageUrl);

            activePageUrl = pageUrl;
            if (!created) {
                sharedBrowser.loadURL(pageUrl);
            }

            // 超时处理
            future.orTimeout(timeoutSec, TimeUnit.SECONDS)
                .exceptionally(e -> null);

            // 结束清理：只解除路由并导航到空白页，不销毁浏览器（保留 Cookie 供下次复用）
            future.whenComplete((url, err) -> {
                // 仅当自己仍是当前任务时才清理——被新任务接管后不得触碰浏览器状态
                if (activeFuture == future) {
                    activeFuture = null;
                    KazumiLog.sniff.debug("sniff done url={} err={}", url, err);
                    Minecraft.getInstance().execute(() -> {
                        // 若重试已接管（activeFuture 非空），不能覆盖刚发起的导航
                        if (activeFuture != null) return;
                        try {
                            if (sharedBrowser != null) {
                                sharedBrowser.loadURL("about:blank");
                            }
                        } catch (Exception ignored) {
                        }
                        // 浏览器加载可能抢走窗口焦点导致鼠标脱离准心，恢复鼠标捕获
                        ClientDisconnectHandler.forceRestoreMouseGrab(Minecraft.getInstance());
                    });
                }
                if (err != null) {
                    KazumiLog.sniff.warn("Sniff failed: {}", err.getMessage());
                }
            });

        } catch (Exception e) {
            if (activeFuture == future) {
                activeFuture = null;
            }
            future.completeExceptionally(e);
        }

        return future;
    }

    /** 创建常驻浏览器（首个嗅探页初始化）并注册一次性 handler；返回是否为本次新建 */
    private static synchronized boolean ensureBrowser(String initialUrl) {
        if (sharedBrowser != null) return false;

        sharedBrowser = MCEF.createBrowser(initialUrl, true);
        sharedBrowserId = sharedBrowser.getIdentifier();

        // 原生网络层拦截（Kazumi shouldInterceptRequest 同款通用方案）：
        // 视频源可能嵌套在 iframe/第三方播放页中，JS 嗅探够不到时，
        // 只要浏览器发出 .m3u8 请求或带 Range 的视频流请求，就在这里报告。
        MCEF.getClient().getHandle().addRequestHandler(new CefRequestHandler() {
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
                        // UA 统一伪装（含空闲期）：mcef.properties 可能未配置（走 CEF 默认旧版 UA）
                        // 或配置了平台不符的值，一律强制重写，保证浏览器所有流量指纹一致
                        String ua = req.getHeaderByName("User-Agent");
                        if (!SPOOFED_UA.equals(ua)) {
                            req.setHeaderByName("User-Agent", SPOOFED_UA, true);
                        }
                        String url = req.getURL();
                        CompletableFuture<String> active = activeFuture;
                        if (active == null) return false;
                        KazumiLog.sniff.debug("[sniff] resource request: {}", url);
                        // Cloudflare 挑战/拦截页特征：出现即说明没到真正的播放器页面
                        if (url != null && (url.contains("/cdn-cgi/challenge-platform/")
                                || url.contains("cf-chl") || url.contains("cf-icon-"))) {
                            cfChallengeDetected = true;
                        }
                        if (br.getIdentifier() == sharedBrowserId && isVideoRequest(req)) {
                            active.complete(url);
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
        });

        // 控制台消息路由：JS 嗅探脚本通过 KAZUMI_VIDEO_URL: 前缀报告直链
        MCEF.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level,
                    String message, String source, int line) {
                if (message != null && message.startsWith(COOKIE_PREFIX)) {
                    String payload = message.substring(COOKIE_PREFIX.length());
                    int sep = payload.indexOf('|');
                    if (sep > 0) {
                        BrowserCookieStore.saveFromBrowser(
                            payload.substring(0, sep), payload.substring(sep + 1));
                    }
                    return false;
                }
                CompletableFuture<String> active = activeFuture;
                if (active == null) return false;
                if (message.startsWith(SNIFF_PREFIX)) {
                    String videoUrl = message.substring(SNIFF_PREFIX.length());
                    KazumiLog.sniff.info("Sniffed video URL: {}", videoUrl);
                    active.complete(videoUrl);
                    return true;
                }
                return false;
            }
        });

        // 页面加载开始即注入 fetch/XHR hook，加载完成后注入完整嗅探脚本。
        // 主页面与所有 iframe 的 onLoadStart/onLoadEnd 都会触发：
        // 解析站（如 7sefun→lmm35）的视频源可能嵌套在第三方播放页 iframe 中。
        MCEF.getClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser b, org.cef.browser.CefFrame frame,
                    org.cef.network.CefRequest.TransitionType transitionType) {
                String pageUrl = activePageUrl;
                if (pageUrl == null) return;
                // 页面加载早期就 hook fetch/XHR，捕获加载过程中的 m3u8 响应
                frame.executeJavaScript(SNIFF_HOOK_SCRIPT, pageUrl, 0);
            }

            @Override
            public void onLoadEnd(CefBrowser b, org.cef.browser.CefFrame frame, int httpStatusCode) {
                KazumiLog.sniff.info("[sniff] page load end: status={} url={}", httpStatusCode, frame.getURL());
                String pageUrl = activePageUrl;
                if (pageUrl == null) return;
                // DOM 就绪后注入完整脚本（video 扫描 + MutationObserver + 轮询兜底）
                frame.executeJavaScript(SNIFF_SCRIPT, pageUrl, 0);
                // 收割本 frame 的 Cookie（含挑战通过后的 cf_clearance）供 java HTTP 请求桥接复用；
                // host 编码进 payload——控制台回调拿不到 frame 信息
                frame.executeJavaScript(
                    "try{console.log('KAZUMI_COOKIES:' + location.host + '|' + document.cookie);}catch(e){}",
                    pageUrl, 0);
            }
        });

        KazumiLog.sniff.debug("shared sniff browser created (id={})", sharedBrowserId);
        return true;
    }

    /**
     * 判断请求是否为视频源（对齐 Kazumi shouldInterceptRequest）：
     * 1) .m3u8 结尾（支持带 query 参数，如 xxx.m3u8?token=...）
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

        // .m3u8 判断需忽略 query 参数（Kazumi 用 uri.path.endsWith('.m3u8')，
        // 直接用 lower.endsWith 会漏掉带签名的播放地址）
        String path = lower;
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) path = path.substring(0, qIdx);

        if (path.endsWith(".m3u8")) {
            return true;
        }

        // 常见视频扩展名直链：部分站点用 MP4/WebM 直接播放且不带 Range header，
        // 仅靠 .m3u8 + Range 判断会漏掉这类请求
        if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")
                || path.endsWith(".ts") || path.endsWith(".mov") || path.endsWith(".flv")
                || path.endsWith(".avi") || path.endsWith(".m4s")) {
            return true;
        }

        Map<String, String> headers = new HashMap<>();
        request.getHeaderMap(headers);
        if (headers.isEmpty()) return false;
        // MCEF 的 header key 大小写不固定，Range / range 都检查
        String range = headers.getOrDefault("Range", headers.get("range"));
        if (range == null || !range.startsWith("bytes=")) return false;
        // 排除静态资源（Range 请求但非视频）
        return !(lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".html")
                || lower.endsWith(".json") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".svg")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".wasm"));
    }
}
