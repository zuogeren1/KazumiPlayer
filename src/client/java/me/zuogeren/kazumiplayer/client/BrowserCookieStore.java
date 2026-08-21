package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.playback.VideoSniffer;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器 Cookie 桥接存储（对齐 Kazumi PluginCookieManager 模式）。
 *
 * 常驻 MCEF 浏览器通过挑战后，嗅探器在页面 onLoadEnd 时收割
 * document.cookie（含 cf_clearance）按 host 存入此处；
 * java HTTP 请求（播放页直取、规则引擎）按域名取回并成对附加
 * Cookie + 伪装 UA——clearance cookie 与签发时的 UA 绑定，
 * 单发 Cookie 不带对应 UA 会让站点重新下发挑战页。
 * 会话级内存态，游戏重启后需浏览器重新过一次挑战。
 */
public final class BrowserCookieStore {

    private static final Map<String, Map<String, String>> cookiesByHost = new ConcurrentHashMap<>();

    private BrowserCookieStore() {}

    /** 嗅探器从页面 JS 收割到 document.cookie 时调用（host 取自 payload） */
    public static void saveFromBrowser(String host, String cookieString) {
        if (host == null || host.isBlank() || cookieString == null || cookieString.isBlank()) return;
        Map<String, String> jar = cookiesByHost.computeIfAbsent(host, k -> new ConcurrentHashMap<>());
        int before = jar.size();
        for (String part : cookieString.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String name = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (!name.isEmpty()) jar.put(name, value);
        }
        if (jar.size() > before) {
            KazumiLog.sniff.debug("Harvested {} cookie(s) for {}", jar.size() - before, host);
        }
    }

    /** 该 URL 对应域名的 Cookie 头值（无则空串） */
    public static String cookieHeaderFor(String url) {
        String host = hostOf(url);
        if (host == null) return "";
        Map<String, String> jar = cookiesByHost.get(host);
        if (jar == null || jar.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 规则请求增强头：有 Cookie 时成对附加伪装 UA（与签发 Cookie 的浏览器 UA 一致） */
    public static Map<String, String> headersFor(String url) {
        String cookie = cookieHeaderFor(url);
        if (cookie.isEmpty()) return Map.of();
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cookie);
        headers.put("User-Agent", VideoSniffer.getSpoofedUa());
        return headers;
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
