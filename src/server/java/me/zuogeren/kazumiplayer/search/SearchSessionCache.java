package me.zuogeren.kazumiplayer.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 搜索会话缓存: sessionId -> 全量结果列表（TTL 10 分钟）
 * 翻页直接从缓存读取，不重复请求 API
 */
public class SearchSessionCache {
    private final TimedCache<Session> sessions = new TimedCache<>("KazumiPlayer-SessionCache-Cleaner", 10);

    /**
     * 创建新搜索会话，返回 sessionId
     */
    public String createSession(String keyword) {
        return sessions.put(new Session(keyword));
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
        if (s == null) return null;
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

    /** 不存在或已过期返回 null */
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void shutdown() { sessions.shutdown(); }

    public static class Session {
        final String keyword;
        final List<BangumiApi.BangumiSubject> results = Collections.synchronizedList(new ArrayList<>());

        Session(String keyword) {
            this.keyword = keyword;
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
