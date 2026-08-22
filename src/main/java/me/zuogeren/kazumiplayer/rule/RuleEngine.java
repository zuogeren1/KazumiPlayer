package me.zuogeren.kazumiplayer.rule;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import me.zuogeren.kazumiplayer.rule.dto.PreparedRuleRequest;
import me.zuogeren.kazumiplayer.rule.dto.RuleChapterResult;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;
import me.zuogeren.kazumiplayer.util.HttpUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RuleEngine {
    private final XPathRuleStrategy xpathStrategy = new XPathRuleStrategy();
    private final ApiRuleStrategy apiStrategy = new ApiRuleStrategy();

    public CompletableFuture<RuleSearchResult> search(Rule rule, String keyword) {
        if (!rule.isXPathSearch()) {
            return execute(apiStrategy.prepareSearchRequest(rule, keyword))
                .thenApply(raw -> apiStrategy.parseSearch(raw, rule))
                .exceptionally(e -> {
                    KazumiLog.rule.warn("Search {} failed: {}", rule.getName(), e.getMessage());
                    return new RuleSearchResult(rule.getName(), List.of(), "",
                            List.of(e.getMessage()));
                });
        }
        return xpathStrategy.search(RuleExecutionConfig.from(rule), keyword)
            .exceptionally(e -> {
                KazumiLog.rule.warn("Search {} failed: {}", rule.getName(), e.getMessage());
                return new RuleSearchResult(rule.getName(), List.of(), "",
                        List.of(e.getMessage()));
            });
    }

    public CompletableFuture<RuleChapterResult> queryChapters(Rule rule, String source) {
        if (!rule.isXPathChapter()) {
            return execute(apiStrategy.prepareChapterRequest(rule, source))
                .thenApply(raw -> apiStrategy.parseChapters(raw, rule, source))
                .exceptionally(e -> {
                    KazumiLog.rule.warn("Chapter {} failed: {}", rule.getName(), e.getMessage());
                    return new RuleChapterResult(List.of(), "", List.of(e.getMessage()));
                });
        }
        return xpathStrategy.queryChapters(RuleExecutionConfig.from(rule), source)
            .exceptionally(e -> {
                KazumiLog.rule.warn("Chapter {} failed: {}", rule.getName(), e.getMessage());
                return new RuleChapterResult(List.of(), "", List.of(e.getMessage()));
            });
    }

    /**
     * 执行 PreparedRuleRequest：组装增强头、序列化 POST body（对齐 Kazumi _DefaultRuleRequestExecutor）。
     * XPath+POST 规则的 body 是 query 参数 Map（form），API 模式暂无 body 模板来源。
     */
    static CompletableFuture<String> execute(PreparedRuleRequest req) {
        Map<String, String> headers = new LinkedHashMap<>(req.headers());
        String body = renderBody(req);
        if (body != null && !headers.containsKey("Content-Type")) {
            boolean json = "json".equalsIgnoreCase(req.bodyType());
            headers.put("Content-Type", json
                    ? "application/json"
                    : "application/x-www-form-urlencoded");
        }
        return HttpUtil.fetch(req.url(), req.method(),
                RuleRequestEnhancer.enhance(req.url(), headers), req.query(), body);
    }

    private static String renderBody(PreparedRuleRequest req) {
        if (!"POST".equalsIgnoreCase(req.method()) || req.body() == null) return null;
        if (req.body() instanceof Map<?, ?> map) {
            if ("json".equalsIgnoreCase(req.bodyType())) {
                return new com.google.gson.Gson().toJson(map);
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!sb.isEmpty()) sb.append('&');
                sb.append(URLEncoder.encode(String.valueOf(e.getKey()), StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
        return String.valueOf(req.body());
    }
}
