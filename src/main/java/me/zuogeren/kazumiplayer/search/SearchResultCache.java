package me.zuogeren.kazumiplayer.search;

import me.zuogeren.kazumiplayer.rule.dto.SearchItem;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 搜索结果临时缓存，用一次性 UUID 映射到 (ruleName, SearchItem)
 */
public class SearchResultCache {
    private static final long TTL_MINUTES = 5;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KazumiPlayer-SearchCache-Cleaner");
        t.setDaemon(true);
        return t;
    });

    public SearchResultCache() {
        cleaner.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 存储搜索结果，返回一次性 ID
     */
    public String store(String ruleName, SearchItem item) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        cache.put(id, new Entry(ruleName, item, System.currentTimeMillis()));
        return id;
    }

    /**
     * 通过 ID 查找
     */
    public Entry lookup(String id) {
        Entry entry = cache.get(id);
        if (entry == null) return null;
        if (isExpired(entry)) {
            cache.remove(id);
            return null;
        }
        return entry;
    }

    /**
     * 获取缓存数量
     */
    public int size() {
        return cache.size();
    }

    private boolean isExpired(Entry e) {
        return System.currentTimeMillis() - e.createdAt > TimeUnit.MINUTES.toMillis(TTL_MINUTES);
    }

    private void cleanExpired() {
        cache.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    public void shutdown() {
        cleaner.shutdown();
    }

    public record Entry(String ruleName, SearchItem item, long createdAt) {}
}
