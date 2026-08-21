package me.zuogeren.kazumiplayer.rule;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import me.zuogeren.kazumiplayer.rule.dto.PreparedRuleRequest;
import me.zuogeren.kazumiplayer.rule.dto.RuleChapterResult;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;
import me.zuogeren.kazumiplayer.util.HttpUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RuleEngine {
    private final XPathRuleStrategy xpathStrategy = new XPathRuleStrategy();
    private final ApiRuleStrategy apiStrategy = new ApiRuleStrategy();

    public CompletableFuture<RuleSearchResult> search(Rule rule, String keyword) {
        if (!rule.isXPathSearch()) {
            PreparedRuleRequest req = apiStrategy.prepareSearchRequest(rule, keyword);
            return HttpUtil.fetch(req.url(), req.method(),
                    RuleRequestEnhancer.enhance(req.url(), req.headers()), req.query())
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
            PreparedRuleRequest req = apiStrategy.prepareChapterRequest(rule, source);
            return HttpUtil.fetch(req.url(), req.method(),
                    RuleRequestEnhancer.enhance(req.url(), req.headers()), req.query())
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
}
