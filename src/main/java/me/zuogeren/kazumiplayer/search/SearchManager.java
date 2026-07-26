package me.zuogeren.kazumiplayer.search;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务端搜索编排: 并行搜索所有已安装规则 (非阻塞)
 */
public class SearchManager {
    private final RuleEngine engine;
    private final SearchResultCache cache;
    private final ForkJoinPool pool = ForkJoinPool.commonPool();

    public SearchManager(RuleEngine engine) {
        this.engine = engine;
        this.cache = new SearchResultCache();
    }

    /**
     * 并行搜索所有规则 (非阻塞)
     */
    public CompletableFuture<SearchResultData> searchAll(Map<String, Rule> rules, String keyword) {
        Map<String, List<SearchResultEntry>> results = new ConcurrentHashMap<>();
        long timeoutMs = Config.CONFIG.searchTimeoutMs.get();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Rule rule : rules.values()) {
            futures.add(engine.search(rule, keyword)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenAccept(result -> {
                    if (result.items().isEmpty()) return;
                    List<SearchResultEntry> entries = new ArrayList<>();
                    for (var item : result.items()) {
                        String id = cache.store(rule.getName(), item);
                        entries.add(new SearchResultEntry(id, item));
                    }
                    results.put(rule.getName(), entries);
                })
                .exceptionally(e -> {
                    if (!(e instanceof TimeoutException)) {
                        KazumiLog.search.warn("Search failed for {}: {}", rule.getName(),
                                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    }
                    return null;
                }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> new SearchResultData(results, cache));
    }

    public SearchResultCache getCache() { return cache; }

    public record SearchResultEntry(String id, me.zuogeren.kazumiplayer.rule.dto.SearchItem item) {}
    public record SearchResultData(Map<String, List<SearchResultEntry>> results, SearchResultCache cache) {}
}
