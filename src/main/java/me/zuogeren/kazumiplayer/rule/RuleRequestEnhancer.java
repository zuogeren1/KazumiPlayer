package me.zuogeren.kazumiplayer.rule;

import java.util.HashMap;
import java.util.Map;

/**
 * 规则 HTTP 请求头增强钩子（common）。
 * 客户端模块注册实现，把浏览器会话中收割的凭据（Cookie/User-Agent）
 * 按域名附加到规则请求上——对齐 Kazumi 的 PluginCookieManager 模式：
 * clearance cookie 与签发时的 UA 绑定，两者必须成对发送。
 * 服务端单独运行时无增强（返回原 headers）。
 */
public final class RuleRequestEnhancer {

    public interface Enhancer {
        /** @return 需要附加的头（如 Cookie、User-Agent），无则返回空 Map */
        Map<String, String> extraHeadersFor(String url);
    }

    private static volatile Enhancer enhancer;

    private RuleRequestEnhancer() {}

    public static void set(Enhancer e) {
        enhancer = e;
    }

    /** 合并增强头到既有 headers（putIfAbsent：调用方显式设置的头优先） */
    public static Map<String, String> enhance(String url, Map<String, String> headers) {
        Enhancer en = enhancer;
        if (en == null) return headers;
        Map<String, String> extra = en.extraHeadersFor(url);
        if (extra == null || extra.isEmpty()) return headers;
        Map<String, String> merged = new HashMap<>(headers);
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return merged;
    }
}
