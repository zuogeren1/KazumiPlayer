package me.zuogeren.kazumiplayer.bilibili;

import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 弹幕接口的字节级取数：{@link HttpUtil#fetch} 面向文本响应（UTF-8 解码后以 String 返回），
 * protobuf 响应经其往返会损坏，且它读取 Mod 配置，故这里保留原始字节。
 * 明确声明 identity：seg.so 实测返回裸 protobuf，任何外层压缩（含 gzip）都会破坏解析；
 * 解包分支只作为中间设备强行压缩时的兜底（JDK 内置 gzip/deflate，无第三方依赖）。
 * 请求地址均为代码内固定的 B 站接口，不接受外部传入的任意主机。
 */
final class BilibiliHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 单个响应体上限：弹幕分段实测数十 KB，超过此值视为异常响应 */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    record Response(int status, byte[] body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private BilibiliHttp() {}

    static CompletableFuture<Response> get(String url, Map<String, String> headers, int timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", HttpUtil.getRandomUserAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept-Encoding", "identity")
                .GET();
        headers.forEach((name, value) -> {
            if (value != null && !value.isBlank()) builder.header(name, value);
        });
        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(BilibiliHttp::toResponse);
    }

    private static Response toResponse(HttpResponse<byte[]> response) {
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        return new Response(response.statusCode(), decode(body, encoding));
    }

    /** 按 Content-Encoding 解包；解包失败时原样返回，由调用方按解析失败处理 */
    private static byte[] decode(byte[] body, String encoding) {
        if (body.length == 0 || encoding.isBlank() || "identity".equalsIgnoreCase(encoding)) {
            return limit(body);
        }
        String lower = encoding.toLowerCase(Locale.ROOT);
        try {
            if (lower.contains("gzip")) {
                return readAll(new GZIPInputStream(new ByteArrayInputStream(body)));
            }
            if (lower.contains("deflate")) {
                try {
                    return readAll(new InflaterInputStream(new ByteArrayInputStream(body)));
                } catch (IOException wrapped) {
                    return readAll(new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(true)));
                }
            }
        } catch (IOException e) {
            KazumiLog.danmaku.warn("[bilibili] response decode failed ({}): {}", encoding, e.getMessage());
        }
        return limit(body);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) break;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] limit(byte[] body) {
        return body.length <= MAX_BODY_BYTES ? body : Arrays.copyOf(body, MAX_BODY_BYTES);
    }
}
