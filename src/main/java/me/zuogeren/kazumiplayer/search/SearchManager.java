package me.zuogeren.kazumiplayer.search;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.dto.RuleSearchResult;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务端搜索编排: 并行搜索所有已安装规则
 */
public class SearchManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final RuleEngine engine;
    private final SearchResultCache cache;

    public SearchManager(RuleEngine engine) {
        this.engine = engine;
        this.cache = new SearchResultCache();
    }

    /**
     * 并行搜索所有规则，返回 (ruleName -> result list) + (id -> entry) 映射
     */
    public SearchResultData searchAll(Map<String, Rule> rules, String keyword) {
        Map<String, List<SearchResultEntry>> results = new ConcurrentHashMap<>();
        int maxConcurrent = Config.CONFIG.maxConcurrentSearches.get();
        long timeoutMs = Config.CONFIG.searchTimeoutMs.get();

        // 限制并发数
        ForkJoinPool pool = new ForkJoinPool(Math.min(maxConcurrent, Math.max(1, rules.size())));

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Rule rule : rules.values()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    String ruleName = rule.getName();
                    try {
                        RuleSearchResult result = engine.search(rule, keyword)
                                .get(timeoutMs, TimeUnit.MILLISECONDS);

                        List<SearchResultEntry> entries = new ArrayList<>();
                        for (var item : result.items()) {
                            String id = cache.store(ruleName, item);
                            entries.add(new SearchResultEntry(id, item));
                        }
                        if (!entries.isEmpty()) {
                            results.put(ruleName, entries);
                        }
                    } catch (TimeoutException e) {
                        LOGGER.warn("Search timeout for {}: {}", ruleName, keyword);
                    } catch (Exception e) {
                        LOGGER.warn("Search failed for {}: {}", ruleName, e.getMessage());
                    }
                }, pool));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutMs * 2, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            LOGGER.warn("Search all timeout or error: {}", e.getMessage());
        } finally {
            pool.shutdown();
        }

        return new SearchResultData(results, cache);
    }

    public SearchResultCache getCache() { return cache; }

    public record SearchResultEntry(String id, me.zuogeren.kazumiplayer.rule.dto.SearchItem item) {}
    public record SearchResultData(Map<String, List<SearchResultEntry>> results, SearchResultCache cache) {}
}
