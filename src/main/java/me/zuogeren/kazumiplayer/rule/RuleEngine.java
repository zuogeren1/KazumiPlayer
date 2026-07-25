package me.zuogeren.kazumiplayer.rule;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.dto.PreparedRuleRequest;
import me.zuogeren.kazumiplayer.rule.dto.RuleChapterResult;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 规则引擎调度器: 根据 searchMode/chapterMode 分发到 XPath 或 API 策略
 */
public class RuleEngine {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final XPathRuleStrategy xpathStrategy = new XPathRuleStrategy();
    private final ApiRuleStrategy apiStrategy = new ApiRuleStrategy();

    public CompletableFuture<RuleSearchResult> search(Rule rule, String keyword) {
        LOGGER.info("[RuleEngine] search '{}' via {} (mode: {})", keyword, rule.getName(), rule.getSearchMode());
        if (!rule.isXPathSearch()) {
            // API 模式
            PreparedRuleRequest req = apiStrategy.prepareSearchRequest(rule, keyword);
            LOGGER.info("[RuleEngine] API URL: {}", req.url());
            return HttpUtil.fetch(req.url(), req.method(), req.headers(), req.query())
                .thenApply(raw -> {
                    LOGGER.info("[RuleEngine] API response: {} bytes", raw.length());
                    return apiStrategy.parseSearch(raw, rule);
                })
                .exceptionally(e -> {
                    LOGGER.error("[RuleEngine] API search FAILED: {}", e.getMessage());
                    return new RuleSearchResult(rule.getName(), List.of(), "", List.of(e.getMessage()));
                });
        }
        // XPath 模式
        RuleExecutionConfig config = RuleExecutionConfig.from(rule);
        return xpathStrategy.search(config, keyword)
            .exceptionally(e -> {
                LOGGER.error("[RuleEngine] XPath search FAILED: {}", e.getMessage());
                return new RuleSearchResult(rule.getName(), List.of(), "", List.of(e.getMessage()));
            });
    }

    public CompletableFuture<RuleChapterResult> queryChapters(Rule rule, String source) {
        if (!rule.isXPathChapter()) {
            // API 模式
            PreparedRuleRequest req = apiStrategy.prepareChapterRequest(rule, source);
            LOGGER.debug("[API Chapter] {} URL: {}", rule.getName(), req.url());
            return HttpUtil.fetch(req.url(), req.method(), req.headers(), req.query())
                .thenApply(raw -> {
                    LOGGER.debug("[API Chapter] {} response: {} bytes", rule.getName(), raw.length());
                    return apiStrategy.parseChapters(raw, rule, source);
                });
        }
        // XPath 模式
        RuleExecutionConfig config = RuleExecutionConfig.from(rule);
        return xpathStrategy.queryChapters(config, source);
    }
}
