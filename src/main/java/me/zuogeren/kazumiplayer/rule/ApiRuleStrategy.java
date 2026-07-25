package me.zuogeren.kazumiplayer.rule;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.dto.*;
import org.slf4j.Logger;

import java.util.List;

/**
 * API 模式: JSONPath 搜索和剧集解析
 * Phase 2 仅提供基础实现，完整的 JSONPath + 模板变量替换在后续完善
 */
public class ApiRuleStrategy {
    private static final Logger LOGGER = LogUtils.getLogger();

    public PreparedRuleRequest prepareSearchRequest(RuleExecutionConfig config, String keyword) {
        // API 搜索暂不实现完整逻辑，回退到 XPath
        LOGGER.warn("API search mode not yet implemented for {}", config.pluginName());
        return PreparedRuleRequest.get(config.baseUrl());
    }

    public RuleSearchResult parseSearch(String raw, RuleExecutionConfig config) {
        return new RuleSearchResult(config.pluginName(), List.of(), raw,
                List.of("API search mode not yet implemented"));
    }

    public PreparedRuleRequest prepareChapterRequest(RuleExecutionConfig config, String source) {
        LOGGER.warn("API chapter mode not yet implemented for {}", config.pluginName());
        return PreparedRuleRequest.get(config.baseUrl());
    }

    public RuleChapterResult parseChapters(String raw, RuleExecutionConfig config,
                                           String source, String baseUrl) {
        return new RuleChapterResult(List.of(), raw,
                List.of("API chapter mode not yet implemented"));
    }
}
