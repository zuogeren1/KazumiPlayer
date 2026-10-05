package me.zuogeren.kazumiplayer.playback.source;

import me.zuogeren.kazumiplayer.util.KazumiLog;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefCallback;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

import net.minecraft.client.Minecraft;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Rinku(CEF) 嗅探浏览器封装——对齐 Kazumi lib/webview/video/impl/video_webview_impl.dart（通用 CEF 实现）。
 *
 * 单实例持有一个常驻 RinkuBrowser，解析任务按实例串行；
 * 切集时 {@link #unloadPage()} 导航 about:blank 释放页面资源，{@link #dispose()} 时才真正关闭浏览器。
 *
 * 解析手段与蓝本一致：
 * 1. 原生网络层拦截（shouldInterceptRequest 同款）：.m3u8 路径或带 Range 的视频流请求
 * 2. onLoadStart 注入 blob/fetch/XHR hook（含 iframe contentWindow 递归注入）
 * 3. onLoadStop 注入视频标签 MutationObserver（标准模式）或 iframe src 监听（legacy 模式）
 * 4. 每秒轮询兜底（命中即停）
 */
public class RinkuSniffBrowser {

    /** 视频源解析事件（对齐 Kazumi VideoParserEvent record） */
    public record ParserEvent(String url, VideoSourceFormat format) {}

    private static final ScheduledExecutorService PARSER_TIMER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kazumiplayer-sniff-parser-timer");
            t.setDaemon(true);
            return t;
        });

    private final String userAgent = UserAgents.getRandomUa();

    private RinkuBrowser browser;
    private int browserId = -1;
    private boolean hasRegisteredHandlers = false;

    private volatile boolean useLegacyParser = false;
    private volatile boolean isIframeLoaded = false;
    private volatile boolean isVideoSourceLoaded = false;

    private Consumer<String> onLog;
    private CompletableFuture<ParserEvent> parsedFuture;

    private ScheduledFuture<?> videoParserTimer;

    /** 订阅 webview 日志流（≈ Kazumi onLog broadcast stream，单订阅者足够） */
    public void setOnLog(Consumer<String> listener) {
        this.onLog = listener;
    }

    /**
     * 一次性事件：下一次解析命中的视频源。
     * 每次 loadUrl 后由 service 取用，命中或超时后失效。
     */
    public CompletableFuture<ParserEvent> nextVideoParsed() {
        CompletableFuture<ParserEvent> future = new CompletableFuture<>();
        parsedFuture = future;
        return future;
    }

    public boolean isVideoSourceLoaded() {
        return isVideoSourceLoaded;
    }

    /**
     * 加载集数页面并开始解析（对齐蓝本 loadUrl：先 unloadPage，重置状态，注入随加载事件进行）。
     * 浏览器首次使用时以目标页直接创建——原生浏览器异步就绪，过早 loadURL 会丢失导航。
     */
    public void loadUrl(String url, boolean useLegacyParser) {
        unloadPage();
        if (!hasRegisteredHandlers) {
            registerHandlers();
            hasRegisteredHandlers = true;
        }
        this.useLegacyParser = useLegacyParser;
        isIframeLoaded = false;
        isVideoSourceLoaded = false;
        KazumiLog.sniff.info("[source] load page: {} (legacyParser={})", url, useLegacyParser);

        if (browser == null) {
            browser = Rinku.createBrowser(url, true);
            browserId = browser.getIdentifier();
            KazumiLog.sniff.info("[source] sniff browser created (id={}, ua={})", browserId, userAgent);
        } else {
            browser.loadURL(url);
        }
    }

    /** 卸载当前页面释放资源（对齐蓝本 unloadPage：停轮询 + 导航 about:blank，不销毁浏览器） */
    public void unloadPage() {
        cancelVideoParserTimer();
        RinkuBrowser b = browser;
        if (b == null) return;
        Runnable navigate = () -> {
            try {
                b.loadURL("about:blank");
            } catch (Exception e) {
                KazumiLog.sniff.debug("unloadPage skipped: {}", e.getMessage());
            }
        };
        // 必须同步执行：loadUrl 先调本方法再立即 loadURL(目标页)，
        // 若此处入队延迟执行会把刚发起的真实导航覆盖成 about:blank
        if (Minecraft.getInstance().isSameThread()) {
            navigate.run();
        } else {
            Minecraft.getInstance().execute(navigate);
        }
    }

    /** 销毁浏览器（须在渲染线程调用；对齐蓝本 dispose） */
    public void dispose() {
        cancelVideoParserTimer();
        ACTIVE_INSTANCES.remove(this);
        RinkuBrowser b = browser;
        browser = null;
        if (b != null) {
            try {
                b.close();
            } catch (Throwable t) {
                KazumiLog.sniff.warn("Failed to close sniff browser: {}", t.getMessage());
            }
        }
    }

    // ---- 事件出口 ----

    private void fireLog(String message) {
        KazumiLog.sniff.info("[source] {}", message);
        Consumer<String> l = onLog;
        if (l != null) l.accept(message);
    }

    private void fireParsed(ParserEvent event) {
        CompletableFuture<ParserEvent> f = parsedFuture;
        if (f != null) f.complete(event);
    }

    // ---- CEF handler ----
    // RinkuClient 对 load/display 事件是多播分发器（其自身实现了 CefLoadHandler/CefDisplayHandler
    // 并占住原生 CefClient 的单槽），必须经 Rinku.getClient().addXxxHandler 注册；
    // 直接 getHandle().addXxxHandler 会顶掉 Rinku 内部管理导致加载事件丢失。
    // CefRequestHandler 无多播封装（原生单槽），因此全局只注册一次，
    // 由 ACTIVE_INSTANCES 按 browserIdentifier 分发到各实例。

    private static final Set<RinkuSniffBrowser> ACTIVE_INSTANCES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean globalHandlersRegistered;

    private void registerHandlers() {
        ACTIVE_INSTANCES.add(this);
        registerGlobalHandlers();
    }

    private static synchronized void registerGlobalHandlers() {
        if (globalHandlersRegistered) return;
        globalHandlersRegistered = true;

        // 页面加载生命周期：onLoadStart 注入 blob/fetch/XHR hook，onLoadEnd 注入标签解析脚本
        Rinku.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser b, CefFrame frame, CefRequest.TransitionType transitionType) {
                for (RinkuSniffBrowser s : ACTIVE_INSTANCES) s.handleLoadStart(b, frame);
            }

            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
                for (RinkuSniffBrowser s : ACTIVE_INSTANCES) s.handleLoadEnd(b, frame, httpStatusCode);
            }
        });

        // 控制台桥分发：JS 脚本以固定前缀回传日志/命中结果
        Rinku.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level,
                    String message, String source, int line) {
                for (RinkuSniffBrowser s : ACTIVE_INSTANCES) {
                    if (s.handles(b) && s.handleConsoleMessage(message)) return true;
                }
                return false;
            }
        });

        // 原生网络层拦截（对齐 shouldInterceptRequest）
        Rinku.getClient().getHandle().addRequestHandler(new CefRequestHandler() {
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
                        RinkuSniffBrowser owner = findById(br.getIdentifier());
                        if (owner == null) return false;
                        // 浏览器流量 UA 统一为本实例创建时随机选定的 UA
                        // （Rinku 无 per-browser CefSettings.userAgent 入口，资源层改写实现同等效果）
                        String currentUa = req.getHeaderByName("User-Agent");
                        if (!owner.userAgent.equals(currentUa)) {
                            req.setHeaderByName("User-Agent", owner.userAgent, true);
                        }
                        owner.interceptRequest(req);
                        return false;
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
            public void onRenderProcessTerminated(CefBrowser b, TerminationStatus status,
                    int errorCode, String errorString) {
            }
        });
    }

    private static RinkuSniffBrowser findById(int identifier) {
        for (RinkuSniffBrowser s : ACTIVE_INSTANCES) {
            if (s.browser != null && s.browserId == identifier) return s;
        }
        return null;
    }

    private boolean handles(CefBrowser b) {
        return browser != null && b.getIdentifier() == browserId;
    }

    private void handleLoadStart(CefBrowser b, CefFrame frame) {
        if (!handles(b)) return;
        String url = frame.getURL();
        if (url == null || "about:blank".equals(url)) return;
        fireLog("started loading: " + url);
        if (!useLegacyParser) {
            fireLog("Injecting blob parser script (onLoadStart): " + url);
            frame.executeJavaScript(SniffScripts.BLOB_PARSER_SCRIPT, url, 0);
        }
    }

    private void handleLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
        if (!handles(b)) return;
        String url = frame.getURL();
        if (url == null || "about:blank".equals(url)) return;
        fireLog("loading completed: status=" + httpStatusCode + " url=" + url);
        // Cloudflare 5xx（如 522 源站超时）/其他错误页：页面内容不是真正的播放器页，
        // 嗅探大概率失败——显式告警便于与站点侧问题区分
        if (httpStatusCode >= 400) {
            KazumiLog.sniff.warn("[source] page returned error status {} (可能为 CDN/防火墙拦截页)，嗅探大概率失败", httpStatusCode);
        }
        if (!useLegacyParser) {
            fireLog("Injecting video tag parser script (onLoadEnd): " + url);
            frame.executeJavaScript(SniffScripts.VIDEO_TAG_PARSER_SCRIPT, url, 0);
        } else {
            fireLog("Injecting JSBridgeDebug script (onLoadEnd): " + url);
            frame.executeJavaScript(SniffScripts.LEGACY_IFRAME_OBSERVER_SCRIPT, url, 0);
        }
        startVideoParserTimer();
    }

    /** 处理一条控制台消息，返回是否消费（阻止继续传播） */
    private boolean handleConsoleMessage(String message) {
        if (message == null) return false;
        if (message.startsWith(SniffScripts.LOG_PREFIX)) {
            fireLog(message.substring(SniffScripts.LOG_PREFIX.length()));
            return false;
        }
        if (message.startsWith(SniffScripts.VIDEO_PREFIX)) {
            handleStandardBridge(message.substring(SniffScripts.VIDEO_PREFIX.length()));
            return true;
        }
        if (message.startsWith(SniffScripts.LEGACY_PREFIX)) {
            handleLegacyBridge(message.substring(SniffScripts.LEGACY_PREFIX.length()));
            return true;
        }
        return false;
    }

    // ---- 解析判定与命中处理（严格对齐蓝本） ----

    /** 对齐 shouldInterceptRequest：legacy 模式或已命中时跳过；广告过滤；m3u8/Range 判定 */
    private void interceptRequest(CefRequest req) {
        if (useLegacyParser || isVideoSourceLoaded) return;
        String url = req.getURL();
        if (url == null) return;
        String lower = url.toLowerCase();
        if (isAdUrl(lower)) return;
        if (isM3u8Url(lower) || isRangeVideoRequest(lower, req)) {
            fireLog("Native intercepted video URL: " + url);
            markResolvedAndNotify(url, VideoSourceFormat.AUTO);
        }
    }

    /** 对齐 VideoBridgeDebug callback（标准模式）：URL 含 http 即视为命中 */
    private void handleStandardBridge(String message) {
        if (message.contains("http") && !isVideoSourceLoaded) {
            fireLog("Loading video source: " + message);
            markResolvedAndNotify(message, VideoSourceFormat.AUTO);
        }
    }

    /** 对齐 JSBridgeDebug callback（legacy 模式）：iframe src 过滤广告后解码出真实视频地址 */
    private void handleLegacyBridge(String message) {
        if ((message.contains("http") || message.startsWith("//"))
                && !message.contains("googleads")
                && !message.contains("googlesyndication.com")
                && !message.contains("prestrain.html")
                && !message.contains("prestrain%2Ehtml")
                && !message.contains("adtrafficquality")) {
            KazumiLog.sniff.debug("[source] Callback received: {}", message);
            String encodedUrl = uriEncodeFull(message);
            String decoded = decodeVideoSource(encodedUrl);
            if (!decoded.equals(encodedUrl)) {
                fireLog("Loading video source " + decoded);
                markResolvedAndNotify(decoded, VideoSourceFormat.AUTO);
            }
        }
    }

    /** 各捕获手段命中的统一收尾（对齐蓝本各处相同的命中序列：置位 → unloadPage → 通知） */
    private synchronized void markResolvedAndNotify(String url, VideoSourceFormat format) {
        if (isVideoSourceLoaded) return;
        isIframeLoaded = true;
        isVideoSourceLoaded = true;
        unloadPage();
        fireParsed(new ParserEvent(url, format));
    }

    // ---- 每秒轮询兜底 ----

    private void startVideoParserTimer() {
        cancelVideoParserTimer();
        videoParserTimer = PARSER_TIMER.scheduleAtFixedRate(() -> {
            try {
                if (isVideoSourceLoaded) {
                    cancelVideoParserTimer();
                    return;
                }
                pollVideoSource();
            } catch (Throwable t) {
                KazumiLog.sniff.debug("parser timer error: {}", t.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void cancelVideoParserTimer() {
        ScheduledFuture<?> f = videoParserTimer;
        videoParserTimer = null;
        if (f != null) f.cancel(false);
    }

    private void pollVideoSource() {
        RinkuBrowser b = browser;
        if (b == null) return;
        String script = useLegacyParser ? SniffScripts.POLL_LEGACY_SCRIPT : SniffScripts.POLL_VIDEO_TAG_SCRIPT;
        try {
            b.executeJavaScript(script, b.getURL(), 0);
        } catch (Throwable t) {
            KazumiLog.sniff.debug("poll skipped: {}", t.getMessage());
        }
    }

    // ---- URL/请求判定（移植 video_webview_impl.dart 底部三个私有方法） ----

    private static boolean isM3u8Url(String lower) {
        try {
            return URI.create(lower).getPath().endsWith(".m3u8");
        } catch (Exception e) {
            int q = lower.indexOf('?');
            String path = q >= 0 ? lower.substring(0, q) : lower;
            return path.endsWith(".m3u8");
        }
    }

    private static boolean isRangeVideoRequest(String lower, CefRequest request) {
        Map<String, String> headers = new HashMap<>();
        request.getHeaderMap(headers);
        if (headers.isEmpty()) return false;
        String range = headers.getOrDefault("Range", headers.get("range"));
        if (range == null || !range.startsWith("bytes=")) return false;
        return !lower.endsWith(".js") && !lower.endsWith(".css")
            && !lower.endsWith(".html") && !lower.endsWith(".json")
            && !lower.endsWith(".png") && !lower.endsWith(".jpg")
            && !lower.endsWith(".gif") && !lower.endsWith(".svg")
            && !lower.endsWith(".woff") && !lower.endsWith(".woff2")
            && !lower.endsWith(".wasm");
    }

    private static boolean isAdUrl(String lower) {
        return lower.contains("googleads")
            || lower.contains("googlesyndication")
            || lower.contains("adtrafficquality")
            || lower.contains("doubleclick");
    }

    // ---- legacy 模式的源地址解码（移植 Kazumi lib/utils/media.dart decodeVideoSource）----

    /**
     * 从 iframe 跳转链接中解码真正的视频地址：
     * 先整体 percent-decode，再从 query 参数值里正则匹配 m3u8/mp4 地址；
     * 未命中参数则原样返回 encodeFull 后的输入。
     */
    static String decodeVideoSource(String iframeUrl) {
        String decodedUrl = uriDecodeFull(iframeUrl);
        java.util.regex.Pattern videoInParam = java.util.regex.Pattern.compile(
            "(http[s]?://.*?\\.m3u8)|(http[s]?://.*?\\.mp4)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

        String matchedUrl = iframeUrl;
        try {
            String rawQuery = URI.create(decodedUrl).getRawQuery();
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    String value = eq >= 0 ? pair.substring(eq + 1) : pair;
                    if (videoInParam.matcher(value).find()) {
                        matchedUrl = value;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // 非法 URI 时保持原地址返回
        }
        return uriEncodeFull(matchedUrl);
    }

    /** Dart Uri.encodeFull 等价：保留 URI 结构字符，其余 percent 编码 UTF-8 */
    static String uriEncodeFull(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (Character.isLetterOrDigit(c) || "-._~!$&'()*+,;=:/?#[]@".indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    /** Dart Uri.decodeFull 等价：percent-decode 为 UTF-8（'+' 不转空格，区别于 URLDecoder） */
    static String uriDecodeFull(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.write(c);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
