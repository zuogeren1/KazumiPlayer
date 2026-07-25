package me.zuogeren.kazumiplayer.rule.dto;

import java.util.List;

/**
 * 剧集列表查询结果
 */
public record RuleChapterResult(
        List<Road> roads,
        String rawResponse,
        List<String> diagnostics) {
}
