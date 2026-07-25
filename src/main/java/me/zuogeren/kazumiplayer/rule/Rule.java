package me.zuogeren.kazumiplayer.rule;

import com.google.gson.annotations.SerializedName;

/**
 * Kazumi 规则 JSON 完整映射。
 * 字段名保持与 JSON key 一致 (如 baseURL 而非 baseUrl)。
 */
public class Rule {
    private String api = "1";
    private String type = "anime";
    private String name = "";
    private String version = "";
    private boolean muliSources = true;

    @SerializedName("baseURL")
    private String baseUrl = "";
    private String referer = "";
    private String userAgent = "";

    // XPath 搜索字段
    @SerializedName("searchURL")
    private String searchUrl = "";
    private String searchList = "";
    private String searchName = "";
    private String searchResult = "";
    private String searchMode = "xpath";

    // XPath 章节字段
    private String chapterRoads = "";
    private String chapterResult = "";
    private String chapterMode = "xpath";

    // HTTP 相关
    private boolean usePost;
    private boolean useLegacyParser;
    private boolean adBlocker;

    // Phase 2: API 模式和反爬虫暂不处理
    private boolean deprecated;

    // --- Getters ---
    public String getApi() { return api; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public boolean isMuliSources() { return muliSources; }
    public String getBaseUrl() { return baseUrl; }
    public String getReferer() { return referer; }
    public String getUserAgent() { return userAgent; }
    public String getSearchUrl() { return searchUrl; }
    public String getSearchList() { return searchList; }
    public String getSearchName() { return searchName; }
    public String getSearchResult() { return searchResult; }
    public String getSearchMode() { return searchMode; }
    public String getChapterRoads() { return chapterRoads; }
    public String getChapterResult() { return chapterResult; }
    public String getChapterMode() { return chapterMode; }
    public boolean isUsePost() { return usePost; }
    public boolean isUseLegacyParser() { return useLegacyParser; }
    public boolean isAdBlocker() { return adBlocker; }
    public boolean isDeprecated() { return deprecated; }

    /**
     * 规则名称只能包含字母数字下划线
     */
    public boolean isValidName() {
        return name != null && name.matches("[a-zA-Z0-9_]+");
    }

    /**
     * baseURL 必须以 http:// 或 https:// 开头
     */
    public boolean isValidBaseUrl() {
        return baseUrl != null && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"));
    }

    /**
     * 是否为 XPath 搜索模式
     */
    public boolean isXPathSearch() {
        return !"api".equals(searchMode);
    }

    /**
     * 是否为 XPath 章节模式
     */
    public boolean isXPathChapter() {
        return !"api".equals(chapterMode);
    }

    @Override
    public String toString() {
        return "Rule{name='" + name + "', version='" + version + "'}";
    }
}
