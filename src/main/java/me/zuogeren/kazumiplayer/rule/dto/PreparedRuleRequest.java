package me.zuogeren.kazumiplayer.rule.dto;

import java.util.Map;

/**
 * 规则引擎生成的 HTTP 请求
 */
public record PreparedRuleRequest(
        String method,
        String url,
        Map<String, String> headers,
        Map<String, String> query,
        String bodyType,
        Object body,
        boolean includeCookies) {

    public static PreparedRuleRequest get(String url) {
        return new PreparedRuleRequest("GET", url, Map.of(), Map.of(), "none", null, false);
    }
}
