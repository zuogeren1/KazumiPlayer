package me.zuogeren.kazumiplayer.search;

import me.zuogeren.kazumiplayer.rule.dto.SearchItem;

/**
 * 搜索结果临时缓存，用一次性 UUID 映射到 (ruleName, SearchItem)
 */
public class SearchResultCache {
    private final TimedCache<Entry> cache = new TimedCache<>("KazumiPlayer-SearchCache-Cleaner", 5);

    /**
     * 存储搜索结果，返回一次性 ID
     */
    public String store(String ruleName, SearchItem item) {
        return cache.put(new Entry(ruleName, item));
    }

    /**
     * 通过 ID 查找；不存在或已过期返回 null
     */
    public Entry lookup(String id) {
        return cache.get(id);
    }

    /**
     * 获取缓存数量
     */
    public int size() {
        return cache.size();
    }

    public void shutdown() {
        cache.shutdown();
    }

    public record Entry(String ruleName, SearchItem item) {}
}
