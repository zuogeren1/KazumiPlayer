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
                    if (val != null) vars.put(entry.getKey(), val);
                }
            }

            if ("delimited".equals(config.format)) {
                diagnostics.add("delimited format not implemented");
            } else {
                List<JsonElement> roadList = SimpleJsonPath.read(root, config.roadsPath);
                int roadIdx = 0;
                for (JsonElement roadObj : roadList) {
                    roadIdx++;
                    String roadName = SimpleJsonPath.readFirst(roadObj, config.roadNamePath);
                    if (roadName == null) roadName = "线路" + roadIdx;

                    List<JsonElement> epList = SimpleJsonPath.read(roadObj, config.episodesPath);
                    List<String> urls = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    int epIdx = 0;

                    for (JsonElement epObj : epList) {
                        epIdx++;
                        try {
                            String epName = SimpleJsonPath.readFirst(epObj, config.episodeNamePath);
                            if (epName == null) epName = "第" + epIdx + "集";
                            String epUrl;

                            if (config.episodeUrlPath != null && !config.episodeUrlPath.isBlank()) {
                                String val = SimpleJsonPath.readFirst(epObj, config.episodeUrlPath);
                                epUrl = val != null ? val : "";
                            } else if (config.episodePage != null) {
                                Map<String, Object> tv = new HashMap<>(vars);
                                tv.put("roadIndex", String.valueOf(roadIdx - 1));
                                tv.put("roadNumber", String.valueOf(roadIdx));
                                tv.put("episodeIndex", String.valueOf(epIdx - 1));
                                tv.put("episodeNumber", String.valueOf(epIdx));
                                tv.put("source", source);
                                tv.put("episodeUrl", "");
                                epUrl = render(config.episodePage.url, tv);
                                if (!config.episodePage.query.isEmpty()) {
                                    StringBuilder qs = new StringBuilder();
                                    for (var qe : config.episodePage.query.entrySet()) {
                                        if (!qs.isEmpty()) qs.append('&');
                                        qs.append(URLEncoder.encode(qe.getKey(), StandardCharsets.UTF_8));
                                        qs.append('=');
                                        qs.append(URLEncoder.encode(render(qe.getValue(), tv), StandardCharsets.UTF_8));
                                    }
                                    epUrl += "?" + qs;
                                }
                            } else {
                                continue;
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
        String url = render(req.url, vars);
        Map<String, String> query = new LinkedHashMap<>();
        for (var entry : req.query.entrySet()) {
            query.put(entry.getKey(), render(entry.getValue(), vars));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (var entry : req.headers.entrySet()) {
            headers.put(entry.getKey(), render(entry.getValue(), vars));
        }
        return new PreparedRuleRequest(req.method, url, headers, query,
                req.bodyType != null ? req.bodyType : "none", null, true);
    }

    private String render(String template, Map<String, Object> vars) {
        if (template == null) return "";
        String result = template;
        for (var entry : vars.entrySet()) {
            String placeholder = "@" + entry.getKey();
            if (result.contains(placeholder)) {
                result = result.replace(placeholder,
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        return result;
    }
}
