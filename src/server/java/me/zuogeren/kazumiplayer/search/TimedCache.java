package me.zuogeren.kazumiplayer.search;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 带周期过期清理的线程安全键值缓存。
 * 每实例持有一个守护清理线程（1 分钟周期），到期项在读取时惰性移除 + 定时批量清扫。
 * 用于搜索结果/搜索会话等短生命周期数据。
 */
public class TimedCache<V> {
    private final long ttlMillis;
    private final Map<String, Timestamped<V>> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    /** @param threadName 清理线程名 @param ttlMinutes 条目存活分钟数 */
    public TimedCache(String threadName, long ttlMinutes) {
        this.ttlMillis = TimeUnit.MINUTES.toMillis(ttlMinutes);
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.MINUTES);
    }

    /** 存入条目并返回一次性 ID */
    public String put(V value) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        cache.put(id, new Timestamped<>(value, System.currentTimeMillis()));
        return id;
    }

    /**
     * 读取条目；不存在或已过期返回 null（过期项顺带移除）
     */
    public V get(String id) {
        var e = cache.get(id);
        if (e == null) return null;
        if (isExpired(e)) {
            cache.remove(id);
            return null;
        }
        return e.value();
    }

    public int size() { return cache.size(); }

    private boolean isExpired(Timestamped<V> e) {
        return System.currentTimeMillis() - e.createdAt() > ttlMillis;
    }

    private void cleanExpired() {
        cache.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    public void shutdown() { cleaner.shutdown(); }

    /** 创建时间由本类统一管理，业务值无需自带时间戳字段 */
    private record Timestamped<V>(V value, long createdAt) {}
}
