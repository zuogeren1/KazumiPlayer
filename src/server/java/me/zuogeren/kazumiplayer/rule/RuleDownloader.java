package me.zuogeren.kazumiplayer.rule;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 从 KazumiRules GitHub 仓库下载规则
 */
public class RuleDownloader {

    /** 远程目录内存缓存：GUI 打开/命令列表读缓存，仅显式刷新时重新拉取 */
    private volatile List<RuleIndex> cachedIndex;

    /**
     * 获取规则目录 (index.json)：
     * force=true 强制网络拉取并更新缓存（GUI「刷新列表」、命令 update/pull-all）；
     * 否则缓存命中直接返回，未命中（服务端启动后首次）拉取一次并缓存。
     */
    public CompletableFuture<List<RuleIndex>> fetchIndex(boolean forceRefresh) {
        if (!forceRefresh) {
            List<RuleIndex> cached = this.cachedIndex;
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        String url = Config.CONFIG.githubRulesRepoUrl.get() + "index.json";
        return HttpUtil.fetch(url)
                .thenApply(raw -> {
                    List<RuleIndex> index = JsonUtil.GSON.fromJson(raw,
                            new TypeToken<List<RuleIndex>>() {}.getType());
                    List<RuleIndex> result = index != null ? index : Collections.emptyList();
                    this.cachedIndex = result;
                    KazumiLog.rule.info("Rule index fetched (force={}), {} entries", forceRefresh, result.size());
                    return result;
                });
    }

    /** 获取规则目录（读缓存优先，无缓存时拉取一次） */
    public CompletableFuture<List<RuleIndex>> fetchIndex() {
        return fetchIndex(false);
    }

    /**
     * 下载单个规则 (name.json)
     */
    public CompletableFuture<Rule> fetchRule(String ruleName) {
        String url = Config.CONFIG.githubRulesRepoUrl.get() + ruleName + ".json";
        return HttpUtil.fetch(url)
                .thenApply(raw -> {
                    Rule rule = JsonUtil.GSON.fromJson(raw, Rule.class);
                    if (rule == null) {
                        throw new RuntimeException("Failed to parse rule: " + ruleName);
                    }
                    KazumiLog.rule.info("Downloaded rule {}: searchMode={}, apiConfig={}, xpathSearchUrl={}",
                            ruleName, rule.getSearchMode(),
                            rule.getSearchApiConfig() != null ? rule.getSearchApiConfig().request.url : "NULL",
                            rule.getSearchUrl());
                    if (!rule.isValidName()) {
                        throw new RuntimeException("Invalid rule name: " + rule.getName());
                    }
                    if (!rule.isValidBaseUrl()) {
                        throw new RuntimeException("Invalid baseUrl for rule: " + rule.getName());
                    }
                    KazumiLog.rule.info("Downloaded rule: {}", rule.getName());
                    return rule;
                });
    }

    /**
     * 把下载/解析链路的异常翻译成用户可读的提示。
     * CompletableFuture 会把原始异常包进 CompletionException，需先解包再匹配。
     */
    public static String friendlyError(String ruleName, Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = String.valueOf(t.getMessage());
        if (msg.contains("HTTP 404")) {
            return "仓库中不存在规则 \"" + ruleName + "\"（注意名称区分大小写）";
        }
        if (t instanceof com.google.gson.JsonSyntaxException || msg.contains("Expected BEGIN_OBJECT")) {
            return "规则文件内容异常（仓库返回了错误页面而非规则 JSON）";
        }
        if (msg.contains("超时")) {
            return "下载超时，请检查网络后重试";
        }
        if (msg.contains("DNS")) {
            return "无法连接规则仓库（DNS 解析失败），请检查网络";
        }
        return msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }
}
