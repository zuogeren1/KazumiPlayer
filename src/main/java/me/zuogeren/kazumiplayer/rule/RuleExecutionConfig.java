package me.zuogeren.kazumiplayer.rule;

/**
 * 从 Rule 提取的不可变执行快照
 */
public record RuleExecutionConfig(
        String pluginName,
        String baseUrl,
        boolean usePost,
        String searchMode,
        String chapterMode,
        String searchUrl,
        String searchList,
        String searchName,
        String searchResult,
        String chapterRoads,
        String chapterResult,
        String userAgent,
        String referer,
        boolean useLegacyParser,
        boolean adBlocker,
        boolean antiCrawlerEnabled,
        int captchaDetectType,
        String captchaDetectValue) {

    public static RuleExecutionConfig from(Rule rule) {
        Rule.AntiCrawlerConfig ac = rule.getAntiCrawlerConfig();
        return new RuleExecutionConfig(
                rule.getName(),
                rule.getBaseUrl(),
                rule.isUsePost(),
                rule.getSearchMode(),
                rule.getChapterMode(),
                rule.getSearchUrl(),
                rule.getSearchList(),
                rule.getSearchName(),
                rule.getSearchResult(),
                rule.getChapterRoads(),
                rule.getChapterResult(),
                rule.getUserAgent(),
                rule.getReferer(),
                rule.isUseLegacyParser(),
                rule.isAdBlocker(),
                ac != null && ac.enabled,
                ac != null ? ac.captchaDetectType : 1,
                ac != null ? ac.captchaDetectValue : "");
    }
}
