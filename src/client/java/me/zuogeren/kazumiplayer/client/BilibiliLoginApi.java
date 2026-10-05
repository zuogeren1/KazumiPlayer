package me.zuogeren.kazumiplayer.client;

import com.google.gson.JsonObject;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * B 站扫码登录（passport 接口）。
 * generate 取二维码链接与 qrcode_key，poll 轮询至手机确认；成功后从响应的 Set-Cookie 收集凭据
 * （SESSDATA 等）。凭据只写入客户端本地配置，不回传服务端。
 */
public final class BilibiliLoginApi {

    private static final String GENERATE_API =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL_API =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String REFERER = "https://www.bilibili.com";

    /** 二维码会话：url 供生成二维码图，qrcodeKey 供轮询 */
    public record QrSession(String url, String qrcodeKey) {}

    /** 轮询状态，映射 B 站 data.code：0 成功 / 86090 已扫码待确认 / 86101 未扫码 / 86038 已失效 */
    public enum State { CONFIRMED, SCANNED, WAITING, EXPIRED, UNKNOWN }

    /** 轮询结果：状态 + 成功时的 Cookie 串 + 原始提示文案 */
    public record PollResult(State state, String cookie, String message) {}

    private BilibiliLoginApi() {}

    /** 申请二维码：返回可编码为二维码图的 url 与轮询用 key */
    public static CompletableFuture<QrSession> generate() {
        return HttpUtil.fetch(GENERATE_API, "GET", headers(), Map.of()).thenApply(body -> {
            JsonObject data = dataOf(body, "二维码生成");
            String url = data.has("url") ? data.get("url").getAsString() : "";
            String key = data.has("qrcode_key") ? data.get("qrcode_key").getAsString() : "";
            if (url.isBlank() || key.isBlank()) {
                throw new IllegalStateException("二维码响应缺少 url/qrcode_key");
            }
            return new QrSession(url, key);
        });
    }

    /** 轮询登录状态；成功时解析 Set-Cookie 得到凭据串 */
    public static CompletableFuture<PollResult> poll(String qrcodeKey) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("qrcode_key", qrcodeKey);
        return HttpUtil.fetchResult(POLL_API, "GET", headers(), params).thenApply(result -> {
            JsonObject root = JsonUtil.GSON.fromJson(result.body(), JsonObject.class);
            JsonObject data = root == null ? null : root.getAsJsonObject("data");
            if (data == null || !data.has("code")) {
                throw new IllegalStateException("轮询响应无法解析");
            }
            int code = data.get("code").getAsInt();
            String message = data.has("message") ? data.get("message").getAsString() : "";
            State state = switch (code) {
                case 0 -> State.CONFIRMED;
                case 86090 -> State.SCANNED;
                case 86101 -> State.WAITING;
                case 86038 -> State.EXPIRED;
                default -> State.UNKNOWN;
            };
            String cookie = state == State.CONFIRMED ? cookieFrom(result.headers()) : "";
            return new PollResult(state, cookie, message);
        });
    }

    /** 从 Set-Cookie 头收集 name=value（丢弃 Path/Domain/Expires 等属性段） */
    private static String cookieFrom(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String raw : entry.getValue()) {
                if (raw == null || raw.isBlank()) continue;
                int semi = raw.indexOf(';');
                String pair = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
                if (pair.isEmpty() || !pair.contains("=")) continue;
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        return headers;
    }

    private static JsonObject dataOf(String body, String what) {
        if (body == null || body.isBlank()) throw new IllegalStateException(what + "响应为空");
        JsonObject root = JsonUtil.GSON.fromJson(body, JsonObject.class);
        if (root == null || !root.has("code")) throw new IllegalStateException(what + "响应无法解析");
        int code = root.get("code").getAsInt();
        if (code != 0) {
            String msg = root.has("message") ? root.get("message").getAsString() : String.valueOf(code);
            throw new IllegalStateException(what + "失败：" + msg + "（code " + code + "）");
        }
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new IllegalStateException(what + "响应缺少 data");
        return data;
    }
}
