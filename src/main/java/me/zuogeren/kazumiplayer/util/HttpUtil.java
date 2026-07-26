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
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 客户端，内置 SSRF 防护和响应大小限制
 */
public class HttpUtil {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Random RANDOM = new Random();

    // 内网地址段
    private static final List<String> BLOCKED_NETWORKS = List.of(
            "10.", "172.16.", "192.168.", "127.", "0."
    );
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
     * 异步 HTTP 请求
     */
    public static CompletableFuture<String> fetch(
            String urlString,
            String method,
            java.util.Map<String, String> headers,
            java.util.Map<String, String> queryParams) {

        CompletableFuture<String> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create(urlString);
                KazumiLog.http.debug("HTTP {} {} (host: {})", method, urlString, uri.getHost());
                checkSsrf(uri);

                // 构建 URL (添加 query params)
                String fullUrl = buildUrl(urlString, queryParams);

                // 构建请求
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(Math.max(5, Config.CONFIG.searchTimeoutMs.get() / 1000)))
                        .header("User-Agent", getRandomUserAgent())
                        .header("Accept-Language", "zh-CN,zh;q=0.9");

                // 添加自定义 headers
                headers.forEach(requestBuilder::header);

                if ("POST".equalsIgnoreCase(method)) {
                    requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
                } else {
                    requestBuilder.GET();
                }

                // 发送请求
                HttpResponse<InputStream> response = CLIENT.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());

                int maxBytes = Config.CONFIG.maxSearchResponseBytes.get();
                String body = readResponseBody(response, maxBytes);

                KazumiLog.http.debug("HTTP {} {} -> {} ({} bytes)",
                        method, uri.getHost(), response.statusCode(), body.length());
                future.complete(body);
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

    /**
     * SSRF 防护: 禁止请求内网地址
     */
    private static void checkSsrf(URI uri) throws SsrfBlockedException {
        String host = uri.getHost();
        if (host == null) {
            throw new SsrfBlockedException(uri, "无法解析主机名");
        }
        String lower = host.toLowerCase();
        String resolvedIp;

        // 检查 localhost
        if (LOCALHOST.equals(lower) || LOCALHOST6.equals(lower)) {
            throw new SsrfBlockedException(uri, "禁止访问 localhost");
        }

        // 检查白名单
        List<? extends String> whitelist = Config.CONFIG.ssrfWhitelist.get();
        for (String w : whitelist) {
            if (lower.equals(w) || lower.endsWith("." + w)) {
                return; // 在白名单中，放行
            }
        }

        // 解析 DNS 并检查是否为内网 IP
        try {
            InetAddress addr = InetAddress.getByName(host);
            resolvedIp = addr.getHostAddress();
        } catch (IOException e) {
            throw new SsrfBlockedException(uri, "无法解析主机: " + e.getMessage());
        }

        // 检查内网 IP
        for (String blocked : BLOCKED_NETWORKS) {
            if (resolvedIp.startsWith(blocked)) {
                throw new SsrfBlockedException(uri,
                        "禁止访问内网地址: " + resolvedIp);
            }
        }

        // 检查是否为私有/站点本地地址
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (IOException e) {
            throw new SsrfBlockedException(uri, "无法解析主机");
        }
        if (addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
            throw new SsrfBlockedException(uri,
                    "禁止访问私有/本地地址: " + addr.getHostAddress());
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
