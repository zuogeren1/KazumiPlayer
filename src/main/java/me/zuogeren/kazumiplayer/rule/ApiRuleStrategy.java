package me.zuogeren.kazumiplayer.rule;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jayway.jsonpath.JsonPath;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.dto.*;
import me.zuogeren.kazumiplayer.util.EpisodeUrlNormalizer;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import org.slf4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * API 模式: JSONPath 搜索和剧集解析
 * 完整移植自 Kazumi Dart lib/services/plugin/api_rule_strategy.dart
 */
public class ApiRuleStrategy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /**
     * 准备 API 搜索请求: 替换 @keyword 占位符
     */
    public PreparedRuleRequest prepareSearchRequest(Rule rule, String keyword) {
        if (rule.getSearchApiConfig() == null) {
            return PreparedRuleRequest.get(rule.getBaseUrl());
        }
        var req = rule.getSearchApiConfig().request;
        Map<String, Object> vars = Map.of("keyword", keyword);
        return buildRequest(rule.getBaseUrl(), req, vars);
    }

    /**
     * 解析 API 搜索响应
     */
    public RuleSearchResult parseSearch(String raw, Rule rule) {
        var config = rule.getSearchApiConfig();
        if (config == null) {
            return new RuleSearchResult(rule.getName(), List.of(), raw,
                List.of("no searchApiConfig"));
        }

        List<SearchItem> items = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        try {
            Object doc = com.jayway.jsonpath.Configuration.defaultConfiguration()
                .jsonProvider().parse(raw);

            // 获取结果列表
            Object list = JsonPath.read(doc, config.listPath);
            if (!(list instanceof net.minidev.json.JSONArray arr)) {
                diagnostics.add("listPath did not return array: " + config.listPath);
                return new RuleSearchResult(rule.getName(), items, raw, diagnostics);
            }

            for (Object item : arr) {
                try {
                    String name = JsonPath.read(item, config.namePath).toString();
                    Object srcObj = JsonPath.read(item, config.sourcePath);
                    String src = srcObj != null ? srcObj.toString() : "";
                    if (!name.isBlank() && !src.isBlank()) {
                        items.add(new SearchItem(name.trim(), src.trim()));
                    }
                } catch (Exception e) {
                    diagnostics.add("Skipped item: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            diagnostics.add("Parse error: " + e.getMessage());
            LOGGER.warn("[API Search] {} parse error: {}", rule.getName(), e.getMessage());
        }

        return new RuleSearchResult(rule.getName(), items, raw, diagnostics);
    }

    /**
     * 准备 API 章节请求: 替换 @source 占位符
     */
    public PreparedRuleRequest prepareChapterRequest(Rule rule, String source) {
        if (rule.getChapterApiConfig() == null) {
            return PreparedRuleRequest.get(rule.getBaseUrl());
        }
        var req = rule.getChapterApiConfig().request;
        Map<String, Object> vars = Map.of("source", source);
        return buildRequest(rule.getBaseUrl(), req, vars);
    }

    /**
     * 解析 API 章节响应
     */
    public RuleChapterResult parseChapters(String raw, Rule rule, String source) {
        var config = rule.getChapterApiConfig();
        if (config == null) {
            return new RuleChapterResult(List.of(), raw, List.of("no chapterApiConfig"));
        }

        List<Road> roads = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        try {
            Object doc = com.jayway.jsonpath.Configuration.defaultConfiguration()
                .jsonProvider().parse(raw);

            // 捕获 variables (如 slug)
            Map<String, String> vars = new HashMap<>();
            if (config.variables != null) {
                for (var entry : config.variables.entrySet()) {
                    try {
                        Object val = JsonPath.read(doc, entry.getValue());
                        if (val != null) vars.put(entry.getKey(), val.toString());
                    } catch (Exception ignored) {}
                }
            }

            if ("delimited".equals(config.format)) {
                // 分隔符格式 (暂未实现，记录诊断)
                diagnostics.add("delimited format not implemented");
            } else {
                // 嵌套格式
                Object roadList = JsonPath.read(doc, config.roadsPath);
                if (!(roadList instanceof net.minidev.json.JSONArray arr)) {
                    diagnostics.add("roadsPath did not return array: " + config.roadsPath);
                    return new RuleChapterResult(roads, raw, diagnostics);
                }

                int roadIdx = 0;
                for (Object roadObj : arr) {
                    roadIdx++;
                    List<String> urls = new ArrayList<>();
                    List<String> names = new ArrayList<>();

                    String roadName;
                    try {
                        roadName = JsonPath.read(roadObj, config.roadNamePath).toString();
                    } catch (Exception e) {
                        roadName = "线路" + roadIdx;
                    }

                    Object epList = JsonPath.read(roadObj, config.episodesPath);
                    if (!(epList instanceof net.minidev.json.JSONArray epArr)) {
                        diagnostics.add("Road " + roadIdx + " episodesPath not array");
                        continue;
                    }

                    int epIdx = 0;
                    for (Object epObj : epArr) {
                        epIdx++;
                        try {
                            String epName = JsonPath.read(epObj, config.episodeNamePath).toString();
                            String epUrl;

                            if (config.episodeUrlPath != null && !config.episodeUrlPath.isBlank()) {
                                Object urlObj = JsonPath.read(epObj, config.episodeUrlPath);
                                epUrl = urlObj != null ? urlObj.toString() : "";
                            } else if (config.episodePage != null) {
                                // 用 episodePage 模板构建 URL
                                Map<String, Object> tv = new HashMap<>(vars);
                                tv.put("roadIndex", String.valueOf(roadIdx - 1));
                                tv.put("roadNumber", String.valueOf(roadIdx));
                                tv.put("episodeIndex", String.valueOf(epIdx - 1));
                                tv.put("episodeNumber", String.valueOf(epIdx));
                                tv.put("source", source);
                                tv.put("episodeUrl", "");
                                epUrl = renderTemplate(config.episodePage.url, tv);
                                if (!config.episodePage.query.isEmpty()) {
                                    StringBuilder qs = new StringBuilder();
                                    for (var qe : config.episodePage.query.entrySet()) {
                                        if (!qs.isEmpty()) qs.append('&');
                                        qs.append(URLEncoder.encode(qe.getKey(), StandardCharsets.UTF_8));
                                        qs.append('=');
                                        qs.append(URLEncoder.encode(renderTemplate(qe.getValue(), tv), StandardCharsets.UTF_8));
                                    }
                                    epUrl += "?" + qs;
                                }
                            } else {
                                diagnostics.add("No episodeUrlPath or episodePage for episode " + epIdx);
                                continue;
                            }

                            urls.add(EpisodeUrlNormalizer.normalize(rule.getBaseUrl(), epUrl));
                            names.add(epName.isEmpty() ? "第" + epIdx + "集" : epName);
                        } catch (Exception e) {
                            diagnostics.add("Skipped episode " + epIdx + ": " + e.getMessage());
                        }
                    }

                    if (!urls.isEmpty()) {
                        roads.add(new Road(roadName, urls, names));
                    }
                }
            }
        } catch (Exception e) {
            diagnostics.add("Parse error: " + e.getMessage());
            LOGGER.warn("[API Chapter] {} parse error: {}", rule.getName(), e.getMessage());
        }

        return new RuleChapterResult(roads, raw, diagnostics);
    }

    // --- 工具方法 ---

    private PreparedRuleRequest buildRequest(String baseUrl, Rule.ApiRequestConfig req,
                                              Map<String, Object> vars) {
        String url = renderTemplate(req.url, vars);
        Map<String, String> query = new LinkedHashMap<>();
        for (var entry : req.query.entrySet()) {
            query.put(entry.getKey(), renderTemplate(entry.getValue(), vars));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (var entry : req.headers.entrySet()) {
            headers.put(entry.getKey(), renderTemplate(entry.getValue(), vars));
        }

        boolean includeCookies = true;
        String bodyType = req.bodyType != null ? req.bodyType : "none";
        Object body = null;

        return new PreparedRuleRequest(req.method, url, headers, query, bodyType, body, includeCookies);
    }

    private String renderTemplate(String template, Map<String, Object> vars) {
        if (template == null) return "";
        String result = template;
        for (var entry : vars.entrySet()) {
            String placeholder = "@" + entry.getKey();
            if (result.contains(placeholder)) {
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                result = result.replace(placeholder, value);
            }
        }
        return result;
    }
}
