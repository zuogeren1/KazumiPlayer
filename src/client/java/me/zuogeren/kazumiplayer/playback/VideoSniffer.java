package me.zuogeren.kazumiplayer.playback;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import me.zuogeren.kazumiplayer.ClientConfig;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
            KazumiLog.sniff.debug("before createBrowser mouseGrabbed={} windowActive={}",
                mc.mouseHandler.isMouseGrabbed(), mc.isWindowActive());
            MCEFBrowser browser = MCEF.createBrowser(pageUrl, true);
            KazumiLog.sniff.debug("after createBrowser mouseGrabbed={} windowActive={}",
                mc.mouseHandler.isMouseGrabbed(), mc.isWindowActive());
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
                    if (frame.isMain()) {
                        browser.executeJavaScript(SNIFF_SCRIPT, pageUrl, 0);
                    }
                }
            });

            // 超时处理
            future.orTimeout(timeoutSec, TimeUnit.SECONDS)
                .exceptionally(e -> null);

            // 清理
            future.whenComplete((url, err) -> {
                MCEF.getClient().removeDisplayHandler(handler);
                browser.close();
                KazumiLog.sniff.debug("sniff done url={} err={}, restoring mouse", url, err);
                // 浏览器创建/关闭可能抢走窗口焦点导致鼠标脱离准心，恢复鼠标捕获
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().mouseHandler.grabMouse());
                Minecraft.getInstance().execute(() ->
                    KazumiLog.sniff.debug("after grab mouseGrabbed={} windowActive={}",
                        Minecraft.getInstance().mouseHandler.isMouseGrabbed(),
                        Minecraft.getInstance().isWindowActive()));
                if (err != null) {
                    KazumiLog.sniff.warn("Sniff failed: {}", err.getMessage());
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }
}
