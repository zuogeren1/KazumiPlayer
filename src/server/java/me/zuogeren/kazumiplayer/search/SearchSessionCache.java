package me.zuogeren.kazumiplayer.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 搜索会话缓存: keyword -> 全量结果列表
 * 翻页直接从缓存读取，不重复请求 API
 */
public class SearchSessionCache {
    private static final long TTL_MINUTES = 10;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KazumiPlayer-SessionCache-Cleaner");
        t.setDaemon(true);
        return t;
    });

    public SearchSessionCache() {
        cleaner.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 创建新搜索会话，返回 sessionId
     */
    public String createSession(String keyword) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        sessions.put(id, new Session(keyword));
        return id;
    }

    /**
     * 向会话追加搜索结果
     */
    public void addResults(String sessionId, List<BangumiApi.BangumiSubject> results) {
        Session s = sessions.get(sessionId);
        if (s != null) s.results.addAll(results);
    }

    /**
     * 从缓存获取指定页的结果
     * @return null 如果会话不存在或已过期
     */
    public PageResult getPage(String sessionId, int page, int pageSize) {
        Session s = sessions.get(sessionId);
        if (s == null || isExpired(s)) {
            if (s != null) sessions.remove(sessionId);
            return null;
        }
        int total = s.results.size();
        int totalPages = total == 0 ? 1 : (total + pageSize - 1) / pageSize;
        int cp = Math.min(page, totalPages);
        int start = (cp - 1) * pageSize;
        int end = Math.min(start + pageSize, total);
        if (start >= total) return PageResult.empty(page, totalPages, total, total == 0);

        List<BangumiApi.BangumiSubject> pageItems = s.results.subList(start, end);
        return new PageResult(sessionId, s.keyword, cp, totalPages, total,
                pageItems, cp < totalPages, total > 0);
    }

    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    private boolean isExpired(Session s) {
        return System.currentTimeMillis() - s.createdAt > TimeUnit.MINUTES.toMillis(TTL_MINUTES);
    }

    private void cleanExpired() {
        sessions.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    public void shutdown() { cleaner.shutdown(); }

    public static class Session {
        final String keyword;
        final long createdAt;
        final List<BangumiApi.BangumiSubject> results = Collections.synchronizedList(new ArrayList<>());

        Session(String keyword) {
            this.keyword = keyword;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public record PageResult(
        String sessionId, String keyword, int page, int totalPages, int total,
        List<BangumiApi.BangumiSubject> items, boolean hasNext, boolean hasResults) {

        public static PageResult empty(int page, int totalPages, int total, boolean hasResults) {
            return new PageResult("", "", page, totalPages, total, List.of(), false, hasResults);
        }
    }
}
