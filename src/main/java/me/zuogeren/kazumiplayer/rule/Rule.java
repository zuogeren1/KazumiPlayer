package me.zuogeren.kazumiplayer.rule;

import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kazumi 规则 JSON 完整映射。
 * 字段名保持与 JSON key 一致 (如 baseURL 而非 baseUrl)。
 */
public class Rule {
    String api = "1";
    String type = "anime";
    String name = "";
    String version = "";
    boolean muliSources = true;

    @SerializedName("baseURL")
    String baseUrl = "";
    String referer = "";
    String userAgent = "";

    // XPath 搜索字段
    @SerializedName("searchURL")
    String searchUrl = "";
    String searchList = "";
    String searchName = "";
    String searchResult = "";
    String searchMode = "xpath";

    // XPath 章节字段
    String chapterRoads = "";
    String chapterResult = "";
    String chapterMode = "xpath";

    // HTTP 相关
    boolean usePost;
    boolean useLegacyParser;
    boolean adBlocker;

    // API 模式配置
    ApiSearchConfig searchApiConfig;
    ApiChapterConfig chapterApiConfig;

    boolean deprecated;

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
    public ApiSearchConfig getSearchApiConfig() { return searchApiConfig; }
    public ApiChapterConfig getChapterApiConfig() { return chapterApiConfig; }
    public boolean isDeprecated() { return deprecated; }

    // --- API 配置内部类 (字段 public，Gson 需要直接访问) ---

    public static class ApiRequestConfig {
        public String method = "GET";
        public String url = "";
        public Map<String, String> headers = new LinkedHashMap<>();
        public Map<String, String> query = new LinkedHashMap<>();
        public String bodyType = "none";
    }

    public static class ApiSearchConfig {
        public ApiRequestConfig request = new ApiRequestConfig();
        public String listPath = "$.data[*]";
        public String namePath = "$.name";
        public String sourcePath = "$.url";
    }

    public static class ApiChapterConfig {
        public ApiRequestConfig request = new ApiRequestConfig();
        public String format = "nested";
        public String roadsPath = "$.data.roads[*]";
        public String roadNamePath = "$.name";
        public String episodesPath = "$.episodes[*]";
        public String episodeNamePath = "$.name";
        public String episodeUrlPath = "$.url";
        public Map<String, String> variables = new LinkedHashMap<>();
        public ApiEpisodePageConfig episodePage;
    }

    public static class ApiEpisodePageConfig {
        public String url = "";
        public Map<String, String> query = new LinkedHashMap<>();
    }

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
