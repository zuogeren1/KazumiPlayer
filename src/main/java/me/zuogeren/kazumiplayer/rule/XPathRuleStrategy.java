package me.zuogeren.kazumiplayer.rule;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.rule.dto.PreparedRuleRequest;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import me.zuogeren.kazumiplayer.rule.dto.RuleChapterResult;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;
import me.zuogeren.kazumiplayer.rule.dto.SearchItem;
import me.zuogeren.kazumiplayer.util.EpisodeUrlNormalizer;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * XPath 模式: HTML 抓取搜索和剧集列表
 * 移植自 Kazumi Dart lib/services/plugin/xpath_rule_strategy.dart
 */
public class XPathRuleStrategy {

    /**
     * 准备搜索请求: 替换 @keyword 占位符
     */
    public PreparedRuleRequest prepareSearchRequest(RuleExecutionConfig config, String keyword) {
        String queryUrl = HttpUtil.replaceKeyword(config.searchUrl(), keyword);

        if (config.usePost()) {
            // POST 模式: 从 URL 提取 query params 作为 body
            String baseUrl = queryUrl;
            Map<String, String> params = Map.of();
            int qIndex = queryUrl.indexOf('?');
            if (qIndex >= 0) {
                baseUrl = queryUrl.substring(0, qIndex);
                params = parseQueryParams(queryUrl.substring(qIndex + 1));
            }
            return new PreparedRuleRequest("POST", baseUrl, Map.of(), params, "form", params, true);
        }

        return new PreparedRuleRequest("GET", queryUrl,
                Map.of("Referer", config.baseUrl() + "/"), Map.of(), "none", null, true);
    }

    /**
     * 解析搜索结果 HTML
     */
    public RuleSearchResult parseSearch(String raw, RuleExecutionConfig config) {
        List<SearchItem> items = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Document doc = Jsoup.parse(raw);

        // 搜索结果容器列表
        org.jsoup.select.Elements containers;
        try {
            containers = doc.selectXpath(config.searchList());
        } catch (Exception e) {
            KazumiLog.rule.warn("XPath searchList failed for {}: {}", config.pluginName(), e.getMessage());
            return new RuleSearchResult(config.pluginName(), items, raw, diagnostics);
        }

        int maxResults = Config.CONFIG.maxSearchResultsPerRule.get();
        for (Element container : containers) {
            if (items.size() >= maxResults) {
                diagnostics.add("Truncated at " + maxResults + " results");
                break;
            }

            try {
                // searchName 和 searchResult 相对容器节点执行
                String name = extractXPathText(container, config.searchName());
                String src = extractXPathHref(container, config.searchResult());

                if (name != null && !name.isBlank() && src != null && !src.isBlank()) {
                    items.add(new SearchItem(name.trim(), src.trim()));
                } else {
                    diagnostics.add("Skipped node: empty name or src");
                }
            } catch (Exception e) {
                diagnostics.add("Skipped node: " + e.getMessage());
            }
        }

        return new RuleSearchResult(config.pluginName(), items, raw, diagnostics);
    }

    /**
     * 准备章节请求
     */
    public PreparedRuleRequest prepareChapterRequest(RuleExecutionConfig config, String source) {
        String url = EpisodeUrlNormalizer.normalize(config.baseUrl(), source);
        return PreparedRuleRequest.get(url);
    }

    /**
     * 解析剧集列表 HTML -> 线路(Road)列表
     */
    public RuleChapterResult parseChapters(String raw, RuleExecutionConfig config) {
        List<Road> roads = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Document doc = Jsoup.parse(raw);

        String chapterRoadsXPath = config.chapterRoads();
        // 如果 chapterRoads 为空，将整个文档视为单个线路
        if (chapterRoadsXPath == null || chapterRoadsXPath.isBlank()) {
            String chapterResultXPath = config.chapterResult();
            if (chapterResultXPath == null || chapterResultXPath.isBlank()) {
                return new RuleChapterResult(roads, raw, List.of("chapterRoads and chapterResult are both empty"));
            }

            List<String> urls = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try {
                Elements episodes = doc.selectXpath(chapterResultXPath);
                int episodeIndex = 1;
                for (Element ep : episodes) {
                    String href = ep.attr("href");
                    if (href == null || href.isBlank()) continue;
                    String name = ep.wholeText().replaceAll("\\s+", "");
                    urls.add(EpisodeUrlNormalizer.normalize(config.baseUrl(), href));
                    names.add(name.isEmpty() ? "第" + episodeIndex + "集" : name);
                    episodeIndex++;
                }
            } catch (Exception e) {
                diagnostics.add("chapterResult XPath error: " + e.getMessage());
            }
            if (!urls.isEmpty()) {
                roads.add(new Road("默认线路", urls, names));
            }
        } else {
            // 标准模式: chapterRoads 定位线路容器，chapterResult 相对容器获取剧集
            try {
                Elements roadNodes = doc.selectXpath(chapterRoadsXPath);
                int roadIndex = 1;
                for (Element roadNode : roadNodes) {
                    List<String> urls = new ArrayList<>();
                    List<String> names = new ArrayList<>();

                    try {
                        Elements episodes = roadNode.selectXpath(relativizeXPath(config.chapterResult()));
                        int epNum = 1;
                        for (Element ep : episodes) {
                            String href = ep.attr("href");
                            if (href == null || href.isBlank()) {
                                diagnostics.add("Skipped episode: empty href");
                                continue;
                            }
                            String name = ep.wholeText().replaceAll("\\s+", "");
                            urls.add(EpisodeUrlNormalizer.normalize(config.baseUrl(), href));
                            names.add(name.isEmpty() ? "第" + epNum + "集" : name);
                            epNum++;

                            if (urls.size() >= 1000) {
                                diagnostics.add("Episode list truncated at 1000");
                                break;
                            }
                        }
                    } catch (Exception e) {
                        diagnostics.add("Road " + roadIndex + " parse error: " + e.getMessage());
                    }

                    if (!urls.isEmpty()) {
                        roads.add(new Road("播放线路" + roadIndex, urls, names));
                    }
                    roadIndex++;
                }
            } catch (Exception e) {
                diagnostics.add("chapterRoads XPath error: " + e.getMessage());
            }
        }

        return new RuleChapterResult(roads, raw, diagnostics);
    }

    /**
     * 执行搜索: 准备请求 -> HTTP GET -> 解析
     */
    public CompletableFuture<RuleSearchResult> search(RuleExecutionConfig config, String keyword) {
        PreparedRuleRequest req = prepareSearchRequest(config, keyword);
        var searchHeaders = RuleRequestEnhancer.enhance(req.url(), req.headers());
        return HttpUtil.fetch(req.url(), req.method(), searchHeaders, req.query())
                .thenApply(raw -> {
                    int maxBytes = Config.CONFIG.maxSearchResponseBytes.get();
                    if (raw.length() > maxBytes) {
                        raw = raw.substring(0, maxBytes);
                    }
                    RuleSearchResult result = parseSearch(raw, config);
                    KazumiLog.rule.info("[Search] {} parsed: {} items, diagnostics: {}",
                            config.pluginName(), result.items().size(), result.diagnostics());
                    return result;
                });
    }

    /**
     * 执行章节查询: 准备请求 -> HTTP GET -> 解析
     */
    public CompletableFuture<RuleChapterResult> queryChapters(RuleExecutionConfig config, String source) {
        PreparedRuleRequest req = prepareChapterRequest(config, source);
        // 与搜索请求一致带 Referer（对齐 Kazumi：referer = baseUrl + "/"），部分 WAF 会校验
        var headers = new java.util.HashMap<>(req.headers());
        headers.putIfAbsent("Referer", config.baseUrl() + "/");
        headers = new java.util.HashMap<>(RuleRequestEnhancer.enhance(req.url(), headers));
        return HttpUtil.fetch(req.url(), req.method(), headers, req.query())
                .thenApply(raw -> {
                    int maxBytes = Config.CONFIG.maxSearchResponseBytes.get();
                    if (raw.length() > maxBytes) {
                        raw = raw.substring(0, maxBytes);
                    }
                    RuleChapterResult result = parseChapters(raw, config);
                    KazumiLog.rule.info("[Chapter] {} parsed: {} roads, diagnostics: {}",
                            config.pluginName(), result.roads().size(), result.diagnostics());
                    return result;
                });
    }

    // --- 工具方法 ---

    /**
     * 将 XPath 转为相对当前节点执行（Kazumi Dart 语义兼容）。
     *
     * Jsoup/JAXP 中 "//xxx" 从文档根解析；而 Kazumi(Dart) 中相对容器节点解析为
     * "容器内所有后代 xxx"。规则里的 searchName/searchResult/chapterResult 通常是
     * 相对容器编写的（如 //a、//div[2]/text()），故加 "." 前缀使其相对当前节点。
     * 绝对路径（/xxx 开头）与普通相对路径保持不变。
     */
    private static String relativizeXPath(String xpath) {
        if (xpath == null || xpath.isBlank()) {
            return xpath;
        }
        String trimmed = xpath.trim();
        if (trimmed.startsWith("//")) {
            return "." + trimmed;
        }
        return xpath;
    }

    private static String extractXPathText(Element node, String xpath) {
        // Jsoup/JAXP 对 "xxx/text()" 步骤支持有坑（返回 0 节点），剥离后缀后取元素文本
        String nodePath = xpath;
        if (nodePath != null && nodePath.trim().endsWith("/text()")) {
            nodePath = nodePath.trim().substring(0, nodePath.trim().length() - "/text()".length());
        }
        Elements els = node.selectXpath(relativizeXPath(nodePath));
        if (els.isEmpty()) return null;
        // 取第一个非空文本：容器自身作为祖先匹配（如 //div[2] 命中容器）时可能为空文本
        for (Element el : els) {
            String text = el.wholeText().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private static String extractXPathHref(Element node, String xpath) {
        Elements els = node.selectXpath(relativizeXPath(xpath));
        if (els.isEmpty()) return null;
        // 取第一个带有效 href 的节点（全文档匹配场景下避免取到导航链接）
        for (Element el : els) {
            String href = el.attr("href").trim();
            if (!href.isEmpty()) {
                return href;
            }
        }
        return null;
    }

    private static Map<String, String> parseQueryParams(String query) {
        // 简单 key=value 解析
        if (query == null || query.isBlank()) return Map.of();
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return map;
    }
}
