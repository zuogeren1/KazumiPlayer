package me.zuogeren.kazumiplayer.rule.dto;

import java.util.List;

/**
 * 规则搜索结果
 */
public record RuleSearchResult(
        String pluginName,
        List<SearchItem> items,
        String rawResponse,
        List<String> diagnostics) {
}
