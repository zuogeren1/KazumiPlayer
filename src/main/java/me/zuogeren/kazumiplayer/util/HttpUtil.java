package me.zuogeren.kazumiplayer.util;
import me.zuogeren.kazumiplayer.util.KazumiLog;


import me.zuogeren.kazumiplayer.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 客户端，内置 SSRF 防护和响应大小限制
 */
public class HttpUtil {
    /** 跟随重定向的上限：每跳都重新过 SSRF 校验，超限视为可疑链路 */
    private static final int MAX_REDIRECTS = 5;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 禁止自动重定向：跳转目标必须逐跳过 SSRF 校验，否则公网 302 可直达内网
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Random RANDOM = new Random();

    private static final String LOCALHOST = "localhost";
    private static final String LOCALHOST6 = "[::1]";

    // 随机 User-Agent 池
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
    };

    /**
     * 异步 GET 请求，带 SSRF 检查和响应大小限制
     */
    public static CompletableFuture<String> fetch(String urlString) {
        return fetch(urlString, "GET", java.util.Map.of(), java.util.Map.of());
    }

    /**
     * 异步 HTTP 请求（无请求体）
     */
    public static CompletableFuture<String> fetch(
            String urlString,
            String method,
            java.util.Map<String, String> headers,
            java.util.Map<String, String> queryParams) {
        return fetch(urlString, method, headers, queryParams, null);
    }

    /**
     * 异步 HTTP 请求。
     *
     * @param body POST 请求体原文（form/json 已由调用方序列化），null 表示无请求体
     */
    public static CompletableFuture<String> fetch(
            String urlString,
            String method,
            java.util.Map<String, String> headers,
            java.util.Map<String, String> queryParams,
            String body) {

        CompletableFuture<String> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                // query 参数只作用于首跳；重定向目标以 Location 为准，不再追加
                String currentUrl = buildUrl(urlString, queryParams);
                // lambda 内可变副本（捕获参数须 effectively final）；303/301/302 对 POST 降级为 GET，307/308 保留方法与 body
                String currentMethod = method;
                String currentBody = body;
                for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                    URI uri = URI.create(currentUrl);
                    KazumiLog.http.debug("HTTP {} {} (host: {})", currentMethod, currentUrl, uri.getHost());
                    checkSsrf(uri);

                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(Duration.ofSeconds(Math.max(5, Config.CONFIG.searchTimeoutMs.get() / 1000)))
                            .header("User-Agent", getRandomUserAgent())
                            .header("Accept-Language", "zh-CN,zh;q=0.9");

                    headers.forEach(requestBuilder::header);

                    if ("POST".equalsIgnoreCase(currentMethod)) {
                        requestBuilder.POST(currentBody != null
                                ? HttpRequest.BodyPublishers.ofString(currentBody, StandardCharsets.UTF_8)
                                : HttpRequest.BodyPublishers.noBody());
                    } else {
                        requestBuilder.GET();
                    }

                    HttpResponse<InputStream> response = CLIENT.send(
                            requestBuilder.build(),
                            HttpResponse.BodyHandlers.ofInputStream());

                    int status = response.statusCode();
                    String location = response.headers().firstValue("Location").orElse(null);
                    boolean redirect = (status == 301 || status == 302 || status == 303
                            || status == 307 || status == 308) && location != null;

                    if (!redirect) {
                        if (status >= 400) {
                            // 非 2xx 不再静默解析成 0 条结果，如实上报（站点宕机/反爬可辨别）
                            KazumiLog.http.warn("HTTP {} {} -> {}", currentMethod, currentUrl, status);
                            future.completeExceptionally(
                                    new RuntimeException("站点返回 HTTP " + status + "（可能宕机或被反爬拦截）"));
                            return;
                        }

                        int maxBytes = Config.CONFIG.maxSearchResponseBytes.get();
                        String responseBody = readResponseBody(response, maxBytes);

                        KazumiLog.http.debug("HTTP {} {} -> {} ({} bytes)",
                                currentMethod, uri.getHost(), response.statusCode(), responseBody.length());
                        future.complete(responseBody);
                        return;
                    }

                    // 重定向：排空并关闭当前响应体后校验跳转目标（相对 Location 以当前 URI 为基准解析）
                    try (InputStream drain = response.body()) {
                        drain.readAllBytes();
                    }
                    URI target = uri.resolve(location.trim());
                    if ("POST".equalsIgnoreCase(currentMethod) && status != 307 && status != 308) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                    KazumiLog.http.debug("HTTP redirect {} -> {}", status, target);
                    currentUrl = target.toString();
                }
                future.completeExceptionally(new RuntimeException(
                        "重定向次数超过 " + MAX_REDIRECTS + "，已中止（可疑链路）"));
            } catch (SsrfBlockedException e) {
                future.completeExceptionally(e);
            } catch (java.net.UnknownHostException e) {
                KazumiLog.http.error("DNS failed for {}: {}", urlString, e.getMessage());
                future.completeExceptionally(new RuntimeException("DNS解析失败: " + e.getMessage(), e));
            } catch (java.net.http.HttpTimeoutException e) {
                KazumiLog.http.warn("HTTP timeout: {}", urlString);
                future.completeExceptionally(new RuntimeException("请求超时", e));
            } catch (java.net.ConnectException e) {
                KazumiLog.http.warn("Connection refused: {} ({})", urlString, e.getMessage());
                future.completeExceptionally(new RuntimeException("连接失败: " + e.getMessage(), e));
            } catch (Exception e) {
                KazumiLog.http.warn("HTTP request failed for {}: {} {}", urlString,
                        e.getClass().getSimpleName(), e.getMessage());
                future.completeExceptionally(new RuntimeException(e.getMessage(), e));
            }
        });

        return future;
    }

    /**
     * 从 HTTP 响应读取 body，限制最大字节数
     */
    private static String readResponseBody(HttpResponse<InputStream> response, int maxBytes)
            throws IOException {
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int totalRead = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                totalRead += n;
                if (totalRead > maxBytes) {
                    KazumiLog.http.warn("Response body exceeds max size ({}), truncating", maxBytes);
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** HTTP 响应快照：状态码、响应头（名已小写）与响应体 */
    public record HttpResult(int status, java.util.Map<String, java.util.List<String>> headers, String body) {}

    /**
     * 与 {@link #fetch} 同一套 SSRF 逐跳校验、重定向与超时策略，但保留响应头。
     * 用于登录轮询这类需要读取 <code>Set-Cookie</code> 的请求；请求体恒为空（GET/POST 无体）。
     */
    public static CompletableFuture<HttpResult> fetchResult(String urlString, String method,
            java.util.Map<String, String> headers, java.util.Map<String, String> queryParams) {
        CompletableFuture<HttpResult> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                String currentUrl = buildUrl(urlString, queryParams);
                String currentMethod = method;
                for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                    URI uri = URI.create(currentUrl);
                    checkSsrf(uri);
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(Duration.ofSeconds(Math.max(5, Config.CONFIG.searchTimeoutMs.get() / 1000)))
                            .header("User-Agent", getRandomUserAgent())
                            .header("Accept-Language", "zh-CN,zh;q=0.9");
                    headers.forEach(requestBuilder::header);
                    if ("POST".equalsIgnoreCase(currentMethod)) {
                        requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
                    } else {
                        requestBuilder.GET();
                    }
                    HttpResponse<InputStream> response = CLIENT.send(
                            requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
                    int status = response.statusCode();
                    String location = response.headers().firstValue("Location").orElse(null);
                    boolean redirect = (status == 301 || status == 302 || status == 303
                            || status == 307 || status == 308) && location != null;
                    if (!redirect) {
                        if (status >= 400) {
                            KazumiLog.http.warn("HTTP {} {} -> {}", currentMethod, currentUrl, status);
                            future.completeExceptionally(new RuntimeException("站点返回 HTTP " + status));
                            return;
                        }
                        String body = readResponseBody(response, Config.CONFIG.maxSearchResponseBytes.get());
                        java.util.Map<String, java.util.List<String>> headerMap = new java.util.LinkedHashMap<>();
                        response.headers().map().forEach(
                                (k, v) -> headerMap.put(k.toLowerCase(java.util.Locale.ROOT), v));
                        future.complete(new HttpResult(status, headerMap, body));
                        return;
                    }
                    try (InputStream drain = response.body()) {
                        drain.readAllBytes();
                    }
                    URI target = uri.resolve(location.trim());
                    if ("POST".equalsIgnoreCase(currentMethod) && status != 307 && status != 308) {
                        currentMethod = "GET";
                    }
                    currentUrl = target.toString();
                }
                future.completeExceptionally(new RuntimeException(
                        "重定向次数超过 " + MAX_REDIRECTS + "，已中止（可疑链路）"));
            } catch (SsrfBlockedException e) {
                future.completeExceptionally(e);
            } catch (Exception e) {
                KazumiLog.http.warn("HTTP request failed for {}: {} {}", urlString,
                        e.getClass().getSimpleName(), e.getMessage());
                future.completeExceptionally(new RuntimeException(e.getMessage(), e));
            }
        });
        return future;
    }

    /**
     * 跟随重定向解析最终 URL（不读取响应体），用于短链展开（如 b23.tv）。
     * 与 {@link #fetch} 共用同一套 SSRF 逐跳校验与跳数上限。
     */
    public static CompletableFuture<String> resolveFinalUrl(String urlString) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                String currentUrl = urlString;
                for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                    URI uri = URI.create(currentUrl);
                    checkSsrf(uri);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(Duration.ofSeconds(10))
                            .header("User-Agent", getRandomUserAgent())
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .GET()
                            .build();
                    HttpResponse<InputStream> response =
                            CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    int status = response.statusCode();
                    String location = response.headers().firstValue("Location").orElse(null);
                    boolean redirect = (status == 301 || status == 302 || status == 303
                            || status == 307 || status == 308) && location != null;
                    try (InputStream body = response.body()) {
                        // 只需要 Location 与状态码：立即释放连接，不读响应体
                    }
                    if (!redirect) {
                        if (status >= 400) {
                            future.completeExceptionally(
                                    new RuntimeException("站点返回 HTTP " + status));
                            return;
                        }
                        KazumiLog.http.debug("Resolved final URL: {} -> {}", urlString, currentUrl);
                        future.complete(currentUrl);
                        return;
                    }
                    currentUrl = uri.resolve(location.trim()).toString();
                }
                future.completeExceptionally(new RuntimeException(
                        "重定向次数超过 " + MAX_REDIRECTS + "，已中止（可疑链路）"));
            } catch (SsrfBlockedException e) {
                future.completeExceptionally(e);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * SSRF 防护: 禁止请求内网地址。
     * 对 host 的全部解析结果逐一校验（round-robin DNS 可能同时返回公网与内网记录）；
     * 校验与后续 CLIENT.send 共享同一 JVM DNS 缓存视图，消除多次解析间的选址漂移窗口。
     */
    private static void checkSsrf(URI uri) throws SsrfBlockedException {
        String host = uri.getHost();
        if (host == null) {
            throw new SsrfBlockedException(uri, "无法解析主机名");
        }
        String lower = host.toLowerCase(Locale.ROOT);

        // 检查 localhost
        if (LOCALHOST.equals(lower) || LOCALHOST6.equals(lower)) {
            throw new SsrfBlockedException(uri, "禁止访问 localhost");
        }

        // 检查白名单（配置项归一化：trim + 小写，避免写大写或带空格静默失效）
        List<? extends String> whitelist = Config.CONFIG.ssrfWhitelist.get();
        for (String w : whitelist) {
            if (w == null) continue;
            String normalized = w.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            if (lower.equals(normalized) || lower.endsWith("." + normalized)) {
                return; // 在白名单中，放行
            }
        }

        // 单次解析取全部地址并逐个校验
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new SsrfBlockedException(uri, "无法解析主机: " + e.getMessage());
        }
        for (InetAddress addr : addresses) {
            assertPrivateAddress(uri, addr);
        }
    }

    /** 结构化判定单个地址是否私网/保留段（字节级，覆盖 IPv4 特殊段与 IPv6 ULA） */
    private static void assertPrivateAddress(URI uri, InetAddress addr) throws SsrfBlockedException {
        if (addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            throw new SsrfBlockedException(uri, "禁止访问私有/本地地址: " + addr.getHostAddress());
        }
        byte[] octets = addr.getAddress();
        if (octets.length == 4) {
            int b0 = octets[0] & 0xFF;
            int b1 = octets[1] & 0xFF;
            boolean cgnatHit = (b0 == 100 && (b1 & 0xC0) == 64);   // 100.64/10 CGNAT（VPC/Docker 常见）
            if (cgnatHit && !Config.CONFIG.ssrfBlockCgnat.get()) return;
            boolean privateV4 = b0 == 0                       // 0.0.0.0/8 "this network"
                    || b0 == 10                                // 10/8 私网
                    || b0 == 127                               // 127/8 回环
                    || (cgnatHit && Config.CONFIG.ssrfBlockCgnat.get())
                    || (b0 == 169 && b1 == 254)                // 169.254/16 链路本地
                    || (b0 == 172 && (b1 & 0xF0) == 16)        // 172.16/12 私网
                    || (b0 == 192 && b1 == 168)                // 192.168/16 私网
                    || b0 >= 240;                              // 240/4 保留段（含 255.255.255.255）
            if (privateV4) {
                throw new SsrfBlockedException(uri, "禁止访问内网地址: " + addr.getHostAddress());
            }
        } else if (octets.length == 16) {
            int first = octets[0] & 0xFF;
            // fc00::/7 Unique Local Address（JDK isSiteLocalAddress 只覆盖 fec0::/10 已废弃段）；
            // Clash/Mihomo 等 TUN 代理常用该段映射被代理域名，故默认放行、由配置开启拦截
            if ((first & 0xFE) == 0xFC && Config.CONFIG.ssrfBlockUlaIpv6.get()) {
                throw new SsrfBlockedException(uri, "禁止访问内网地址: " + addr.getHostAddress());
            }
        }
    }

    private static String buildUrl(String url, java.util.Map<String, String> params) {
        if (params.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        if (!url.contains("?")) sb.append('?');
        else if (!url.endsWith("&")) sb.append('&');
        boolean first = !url.contains("?");
        for (var entry : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    public static String getRandomUserAgent() {
        return USER_AGENTS[RANDOM.nextInt(USER_AGENTS.length)];
    }

    /**
     * 替换 URL 中的 @keyword 占位符
     */
    public static String replaceKeyword(String urlTemplate, String keyword) {
        return urlTemplate.replace("@keyword", URLEncoder.encode(keyword, StandardCharsets.UTF_8));
    }

    public static class SsrfBlockedException extends Exception {
        private final URI uri;

        public SsrfBlockedException(URI uri, String reason) {
            super("SSRF blocked: " + uri.getHost() + " - " + reason);
            this.uri = uri;
        }

        public URI getUri() { return uri; }
    }
}
