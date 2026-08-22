package me.zuogeren.kazumiplayer.playback.source;

/**
 * 嗅探注入脚本集中管理——忠实移植 Kazumi lib/webview/video/impl/video_webview_impl.dart 的内联脚本。
 *
 * Dart 侧通过 flutter_inappwebview 的 addJavaScriptHandler 桥回传消息；
 * java-cef 无等价 API，翻译为 console.log 固定前缀，由 CEF onConsoleMessage 分发：
 *   LogBridge         → KAZUMI_LOG:   （调试日志，转发到 sniff logger）
 *   VideoBridgeDebug  → KAZUMI_VIDEO: （标准模式命中视频源）
 *   JSBridgeDebug     → KAZUMI_JS:    （legacy 模式上报 iframe src）
 */
final class SniffScripts {

    static final String LOG_PREFIX = "KAZUMI_LOG:";
    static final String VIDEO_PREFIX = "KAZUMI_VIDEO:";
    static final String LEGACY_PREFIX = "KAZUMI_JS:";

    private SniffScripts() {}

    /**
     * onLoadStart 注入的 blob/fetch/XHR 解析脚本（非 legacy 模式）。
     * 含主文档与 iframe contentWindow 的 Response.text / XHR.open hook，
     * 以及对动态新增 iframe 的递归注入监听。
     */
    static final String BLOB_PARSER_SCRIPT = """
        try { console.log('KAZUMI_LOG:' + 'BlobParser script loaded: ' + window.location.href); } catch(e) {}
        const _r_text = window.Response.prototype.text;
        window.Response.prototype.text = function () {
            return new Promise((resolve, reject) => {
                _r_text.call(this).then((text) => {
                    resolve(text);
                    if (text.trim().startsWith("#EXTM3U")) {
                        console.log('KAZUMI_LOG:' + 'M3U8 source found: ' + this.url);
                        console.log('KAZUMI_VIDEO:' + this.url);
                    }
                }).catch(reject);
            });
        }

        const _open = window.XMLHttpRequest.prototype.open;
        window.XMLHttpRequest.prototype.open = function (...args) {
            this.addEventListener("load", () => {
                try {
                    let content = this.responseText;
                    if (content.trim().startsWith("#EXTM3U")) {
                        console.log('KAZUMI_LOG:' + 'M3U8 source found: ' + args[1]);
                        console.log('KAZUMI_VIDEO:' + args[1]);
                    };
                } catch {}
            });
            return _open.apply(this, args);
        };

        function injectIntoIframe(iframe) {
          try {
            const iframeWindow = iframe.contentWindow;
            if (!iframeWindow) return;

            const iframe_r_text = iframeWindow.Response.prototype.text;
            iframeWindow.Response.prototype.text = function () {
              return new Promise((resolve, reject) => {
                iframe_r_text.call(this).then((text) => {
                  resolve(text);
                  if (text.trim().startsWith("#EXTM3U")) {
                    console.log('KAZUMI_LOG:' + 'M3U8 source found in iframe: ' + this.url);
                    console.log('KAZUMI_VIDEO:' + this.url);
                  }
                }).catch(reject);
              });
            }

            const iframe_open = iframeWindow.XMLHttpRequest.prototype.open;
            iframeWindow.XMLHttpRequest.prototype.open = function (...args) {
              this.addEventListener("load", () => {
                try {
                  let content = this.responseText;
                  if (content.trim().startsWith("#EXTM3U") && args[1] !== null && args[1] !== undefined) {
                    console.log('KAZUMI_LOG:' + 'M3U8 source found in iframe: ' + args[1]);
                    console.log('KAZUMI_VIDEO:' + args[1]);
                  };
                } catch {}
              });
              return iframe_open.apply(this, arguments);
            }
          } catch (e) {
            console.error('iframe inject failed:', e);
          }
        }

        function setupIframeListeners() {
          document.querySelectorAll('iframe').forEach(iframe => {
            if (iframe.contentDocument) {
              injectIntoIframe(iframe);
            }
            iframe.addEventListener('load', () => injectIntoIframe(iframe));
          });

          const observer = new MutationObserver(mutations => {
            mutations.forEach(mutation => {
              if (mutation.type === 'childList') {
                mutation.addedNodes.forEach(node => {
                  if (node.nodeName === 'IFRAME') {
                    node.addEventListener('load', () => injectIntoIframe(node));
                  }
                  if (node.querySelectorAll) {
                    node.querySelectorAll('iframe').forEach(iframe => {
                      iframe.addEventListener('load', () => injectIntoIframe(iframe));
                    });
                  }
                });
              }
            });
          });

          if (document.body) {
            observer.observe(document.body, { childList: true, subtree: true });
          } else {
            document.addEventListener('DOMContentLoaded', () => {
              observer.observe(document.body, { childList: true, subtree: true });
            });
          }
        }

        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', setupIframeListeners);
        } else {
          setupIframeListeners();
        }
        """;

    /**
     * onLoadStop 注入的视频标签解析脚本（非 legacy 模式）：
     * MutationObserver 监听新增/属性变化的 video 元素并提取 src。
     */
    static final String VIDEO_TAG_PARSER_SCRIPT = """
        console.log('KAZUMI_LOG:' + 'VideoTagParser script loaded: ' + window.location.href);
        const _observer = new MutationObserver((mutations) => {
          console.log('KAZUMI_LOG:' + 'Scanning for video elements...');
          for (const mutation of mutations) {
            if (mutation.type === "attributes" && mutation.target.nodeName === "VIDEO") {
              if (processVideoElement(mutation.target)) return;
              continue;
            }
            for (const node of mutation.addedNodes) {
              if (node.nodeName === "VIDEO") {
                if (processVideoElement(node)) return;
              }
              if (node.querySelectorAll) {
                for (const video of node.querySelectorAll("video")) {
                  if (processVideoElement(video)) return;
                }
              }
            }
          }
        });
        function processVideoElement(video) {
          console.log('KAZUMI_LOG:' + 'Scanning video element for source URL');
          let src = video.getAttribute('src');
          if (src && src.trim() !== '' && !src.startsWith('blob:') && !src.includes('googleads')) {
            _observer.disconnect();
            console.log('KAZUMI_LOG:' + 'VIDEO source found: ' + src);
            console.log('KAZUMI_VIDEO:' + src);
            return true;
          }
          const sources = video.getElementsByTagName('source');
          for (let source of sources) {
            src = source.getAttribute('src');
            if (src && src.trim() !== '' && !src.startsWith('blob:') && !src.includes('googleads')) {
              _observer.disconnect();
              console.log('KAZUMI_LOG:' + 'VIDEO source found (source tag): ' + src);
              console.log('KAZUMI_VIDEO:' + src);
              return true;
            }
          }
        }

        function setupVideoProcessing() {
          for (const video of document.querySelectorAll("video")) {
            if (processVideoElement(video)) return;
          }
          _observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['src']
          });
        }
        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', setupVideoProcessing);
        } else {
          setupVideoProcessing();
        }
        """;

    /**
     * onLoadStop 注入的 iframe 监听脚本（legacy 模式）：
     * MutationObserver 扫描 iframe 元素并将其 src 上报给 legacy 桥，
     * 由宿主侧 decodeVideoSource 从跳转链接中解码真正的视频地址。
     */
    static final String LEGACY_IFRAME_OBSERVER_SCRIPT = """
        console.log('KAZUMI_LOG:' + 'JSBridgeDebug script loaded: ' + window.location.href);
        function processIframeElement(iframe) {
          console.log('KAZUMI_LOG:' + 'Processing iframe element');
          let src = iframe.getAttribute('src');
          if (src) {
            console.log('KAZUMI_JS:' + src);
          }
        }

        const _observer = new MutationObserver((mutations) => {
          console.log('KAZUMI_LOG:' + 'Scanning for iframes...');
          mutations.forEach(mutation => {
            if (mutation.type === 'attributes' && mutation.target.nodeName === 'IFRAME') {
              processIframeElement(mutation.target);
            } else {
              mutation.addedNodes.forEach(node => {
                if (node.nodeName === 'IFRAME') processIframeElement(node);
                if (node.querySelectorAll) {
                  node.querySelectorAll('iframe').forEach(processIframeElement);
                }
              });
            }
          });
        });

        _observer.observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ['src']
        });
        """;

    /** 每秒轮询兜底脚本（非 legacy 模式）：扫描已有 video/source 标签 */
    static final String POLL_VIDEO_TAG_SCRIPT = """
        (function() {
          var videos = document.querySelectorAll('video');
          for (var i = 0; i < videos.length; i++) {
            var src = videos[i].getAttribute('src');
            if (src && src.trim() !== '' && !src.startsWith('blob:') && !src.includes('googleads')) {
              console.log('KAZUMI_LOG:' + 'VIDEO source found: ' + src);
              console.log('KAZUMI_VIDEO:' + src);
              return;
            }
            var sources = videos[i].getElementsByTagName('source');
            for (var j = 0; j < sources.length; j++) {
              src = sources[j].getAttribute('src');
              if (src && src.trim() !== '' && !src.startsWith('blob:') && !src.includes('googleads')) {
                console.log('KAZUMI_LOG:' + 'VIDEO source found (source tag): ' + src);
                console.log('KAZUMI_VIDEO:' + src);
                return;
              }
            }
          }
        })();
        """;

    /** 每秒轮询兜底脚本（legacy 模式）：扫描 iframe src 上报 */
    static final String POLL_LEGACY_SCRIPT = """
        (function() {
          var iframes = document.querySelectorAll('iframe');
          for (var i = 0; i < iframes.length; i++) {
            var src = iframes[i].getAttribute('src');
            if (src) {
              console.log('KAZUMI_JS:' + src);
            }
          }
        })();
        """;
}
