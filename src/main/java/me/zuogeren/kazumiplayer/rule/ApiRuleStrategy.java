package me.zuogeren.kazumiplayer.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.zuogeren.kazumiplayer.rule.dto.*;
import me.zuogeren.kazumiplayer.util.EpisodeUrlNormalizer;
import me.zuogeren.kazumiplayer.util.HttpUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * API 模式: JSONPath 搜索和剧集解析
 * 使用 SimpleJsonPath (无外部依赖)
 */
public class ApiRuleStrategy {

    public PreparedRuleRequest prepareSearchRequest(Rule rule, String keyword) {
        if (rule.getSearchApiConfig() == null) {
            return PreparedRuleRequest.get(rule.getBaseUrl());
        }
        var req = rule.getSearchApiConfig().request;
        return buildRequest(req, Map.of("keyword", keyword));
    }

    public RuleSearchResult parseSearch(String raw, Rule rule) {
        var config = rule.getSearchApiConfig();
        if (config == null) {
            return new RuleSearchResult(rule.getName(), List.of(), raw, List.of("no searchApiConfig"));
        }

        List<SearchItem> items = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        try {
            JsonElement root = new com.google.gson.JsonParser().parse(raw);
            List<JsonElement> list = SimpleJsonPath.read(root, config.listPath);
            for (JsonElement item : list) {
                try {
                    String name = SimpleJsonPath.readFirst(item, config.namePath);
                    String src = SimpleJsonPath.readFirst(item, config.sourcePath);
                    if (name != null && !name.isBlank() && src != null && !src.isBlank()) {
                        items.add(new SearchItem(name.trim(), src.trim()));
                    }
                } catch (Exception e) {
                    diagnostics.add("Skipped item: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            diagnostics.add("Parse error: " + e.getMessage());
        }

        return new RuleSearchResult(rule.getName(), items, raw, diagnostics);
    }

    public PreparedRuleRequest prepareChapterRequest(Rule rule, String source) {
        if (rule.getChapterApiConfig() == null) {
            return PreparedRuleRequest.get(rule.getBaseUrl());
        }
        return buildRequest(rule.getChapterApiConfig().request, Map.of("source", source));
    }

    public RuleChapterResult parseChapters(String raw, Rule rule, String source) {
        var config = rule.getChapterApiConfig();
        if (config == null) {
            return new RuleChapterResult(List.of(), raw, List.of("no chapterApiConfig"));
        }

        List<Road> roads = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        try {
            JsonElement root = new com.google.gson.JsonParser().parse(raw);

            // 捕获 variables
            Map<String, String> vars = new HashMap<>();
            if (config.variables != null) {
                for (var entry : config.variables.entrySet()) {
                    String val = SimpleJsonPath.readFirst(root, entry.getValue());
                    if (val != null) {
                        vars.put(entry.getKey(), val);
                    } else {
                        diagnostics.add("章节响应变量 " + entry.getKey() + " 未匹配到值: " + entry.getValue());
                    }
                }
            }

            if ("delimited".equals(config.format)) {
                diagnostics.add("delimited format not implemented");
            } else {
                List<JsonElement> roadList = SimpleJsonPath.read(root, config.roadsPath);
                int roadIdx = 0;
                for (JsonElement roadObj : roadList) {
                    roadIdx++;
                    // 空 JSONPath 不能求值——会把整个线路子树当名字返回
                    boolean hasRoadName = config.roadNamePath != null && !config.roadNamePath.isBlank();
                    String roadName = hasRoadName ? SimpleJsonPath.readFirst(roadObj, config.roadNamePath) : null;
                    if (roadName == null) roadName = "线路" + roadIdx;

                    List<JsonElement> epList = SimpleJsonPath.read(roadObj, config.episodesPath);
                    List<String> urls = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    int epIdx = 0;

                    for (JsonElement epObj : epList) {
                        epIdx++;
                        try {
                            boolean hasEpName = config.episodeNamePath != null && !config.episodeNamePath.isBlank();
                            String epName = hasEpName ? SimpleJsonPath.readFirst(epObj, config.episodeNamePath) : null;
                            if (epName == null) epName = "第" + epIdx + "集";

                            // 对齐 Kazumi _resolveEpisodeUrl：episodeUrlPath 提取的原始值经
                            // @episodeUrl 代入 episodePage.url 模板生成播放页——两者是配合关系，
                            // 不是二选一；无模板时才把原始值直接当 URL
                            String rawUrl = (config.episodeUrlPath != null && !config.episodeUrlPath.isBlank())
                                    ? SimpleJsonPath.readFirst(epObj, config.episodeUrlPath)
                                    : null;
                            String epUrl;

                            if (config.episodePage != null) {
                                if (config.episodePage.url == null || config.episodePage.url.isBlank()) {
                                    diagnostics.add("播放页地址模板不能为空");
                                    continue;
                                }
                                Map<String, Object> tv = new HashMap<>(vars);
                                tv.put("roadIndex", String.valueOf(roadIdx - 1));
                                tv.put("roadNumber", String.valueOf(roadIdx));
                                tv.put("episodeIndex", String.valueOf(epIdx - 1));
                                tv.put("episodeNumber", String.valueOf(epIdx));
                                tv.put("source", source);
                                tv.put("episodeUrl", rawUrl != null ? rawUrl : "");
                                epUrl = render(config.episodePage.url, tv, true);
                                epUrl = mergeEpisodePageQuery(epUrl, config.episodePage.query, tv);
                            } else {
                                if (rawUrl == null || rawUrl.isBlank()) {
                                    diagnostics.add("线路 " + roadIdx + " 剧集 " + epIdx + " 缺少 URL，已跳过");
                                    continue;
                                }
                                epUrl = rawUrl;
                            }

                            urls.add(EpisodeUrlNormalizer.normalize(rule.getBaseUrl(), epUrl));
                            names.add(epName);
                        } catch (Exception e) {
                            diagnostics.add("Skip ep " + epIdx + ": " + e.getMessage());
                        }
                    }
                    if (!urls.isEmpty()) {
                        roads.add(new Road(roadName, urls, names));
                    }
                }
            }
        } catch (Exception e) {
            diagnostics.add("Parse error: " + e.getMessage());
        }

        return new RuleChapterResult(roads, raw, diagnostics);
    }

    private PreparedRuleRequest buildRequest(Rule.ApiRequestConfig req, Map<String, Object> vars) {
        // URL 中的变量值需 percent 编码（对齐 Kazumi _renderTemplate encode:true），
        // query/headers 的值由 buildUrl 统一编码，此处不重复
        String url = render(req.url, vars, true);
        Map<String, String> query = new LinkedHashMap<>();
        for (var entry : req.query.entrySet()) {
            query.put(entry.getKey(), render(entry.getValue(), vars, false));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (var entry : req.headers.entrySet()) {
            headers.put(render(entry.getKey(), vars, false), render(entry.getValue(), vars, false));
        }
        return new PreparedRuleRequest(req.method, url, headers, query,
                req.bodyType != null ? req.bodyType : "none", null, true);
    }

    private String render(String template, Map<String, Object> vars, boolean encodeValue) {
        if (template == null) return "";
        String result = template;
        for (var entry : vars.entrySet()) {
            String placeholder = "@" + entry.getKey();
            if (result.contains(placeholder)) {
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                result = result.replace(placeholder, encodeValue ? encodeComponent(value) : value);
            }
        }
        return result;
    }

    /** Dart Uri.encodeComponent 语义：percent 编码且空格为 %20（URLEncoder 的 + 会破坏 path） */
    private static String encodeComponent(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 合并 episodePage.query 到模板渲染出的 URL（对齐 Kazumi uri.replace(queryParameters: merged)：
     * 模板 URL 自带的 query 保留，page.query 渲染后覆盖同名键）
     */
    private String mergeEpisodePageQuery(String url, Map<String, String> extraQuery, Map<String, Object> tv) {
        if (extraQuery == null || extraQuery.isEmpty()) return url;
        Map<String, String> merged = new LinkedHashMap<>();
        int qIdx = url.indexOf('?');
        if (qIdx >= 0) {
            for (String pair : url.substring(qIdx + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                merged.put(eq >= 0 ? pair.substring(0, eq) : pair,
                        eq >= 0 ? pair.substring(eq + 1) : "");
            }
            url = url.substring(0, qIdx);
        }
        for (var e : extraQuery.entrySet()) {
            merged.put(encodeComponent(render(e.getKey(), tv, false)),
                    encodeComponent(render(e.getValue(), tv, false)));
        }
        StringBuilder sb = new StringBuilder(url).append('?');
        boolean first = true;
        for (var e : merged.entrySet()) {
            if (!first) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
