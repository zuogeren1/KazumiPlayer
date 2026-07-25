package me.zuogeren.kazumiplayer.rule;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.dto.PreparedRuleRequest;
import me.zuogeren.kazumiplayer.rule.dto.RuleChapterResult;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * 规则引擎调度器: 根据 searchMode/chapterMode 分发到 XPath 或 API 策略
 */
public class RuleEngine {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final XPathRuleStrategy xpathStrategy = new XPathRuleStrategy();
    private final ApiRuleStrategy apiStrategy = new ApiRuleStrategy();

    /**
     * 执行搜索
     */
    public CompletableFuture<RuleSearchResult> search(Rule rule, String keyword) {
        RuleExecutionConfig config = RuleExecutionConfig.from(rule);

        if (rule.isXPathSearch()) {
            return xpathStrategy.search(config, keyword);
        } else {
            // API 模式 (Phase 2 暂不完整实现)
            PreparedRuleRequest req = apiStrategy.prepareSearchRequest(config, keyword);
            return me.zuogeren.kazumiplayer.util.HttpUtil.fetch(req.url())
                    .thenApply(raw -> apiStrategy.parseSearch(raw, config));
        }
    }

    /**
     * 查询剧集列表
     */
    public CompletableFuture<RuleChapterResult> queryChapters(Rule rule, String source) {
        RuleExecutionConfig config = RuleExecutionConfig.from(rule);

        if (rule.isXPathChapter()) {
            return xpathStrategy.queryChapters(config, source);
        } else {
            PreparedRuleRequest req = apiStrategy.prepareChapterRequest(config, source);
            return me.zuogeren.kazumiplayer.util.HttpUtil.fetch(req.url())
                    .thenApply(raw -> apiStrategy.parseChapters(raw, config, source, rule.getBaseUrl()));
        }
    }
}
