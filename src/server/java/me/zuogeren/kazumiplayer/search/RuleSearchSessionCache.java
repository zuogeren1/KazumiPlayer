package me.zuogeren.kazumiplayer.search;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 规则搜索会话缓存: sessionId -> 全量搜索结果（含关键词、是否全规则、原始 SearchResultData）
 * 翻页直接从缓存读取，不重复请求源站（对齐 SearchSessionCache 对 bgm.tv 搜索的处理）。
 */
public class RuleSearchSessionCache {
    private static final long TTL_MINUTES = 10;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KazumiPlayer-RuleSessionCache-Cleaner");
        t.setDaemon(true);
        return t;
    });

    public RuleSearchSessionCache() {
        cleaner.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 创建规则搜索会话，返回 sessionId
     */
    public String createSession(String ruleName, String keyword, SearchManager.SearchResultData data) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        sessions.put(id, new Session(id, ruleName, keyword, data, System.currentTimeMillis()));
        return id;
    }

    /**
     * 获取会话；不存在或已过期返回 null
     */
    public Session getSession(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null || isExpired(s)) {
            if (s != null) sessions.remove(sessionId);
            return null;
        }
        return s;
    }

    private boolean isExpired(Session s) {
        return System.currentTimeMillis() - s.createdAt > TimeUnit.MINUTES.toMillis(TTL_MINUTES);
    }

    private void cleanExpired() {
        sessions.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    public void shutdown() {
        cleaner.shutdown();
    }

    public static class Session {
        public final String sessionId;
        /** 指定规则名；null = 全部规则搜索 */
        public final String ruleName;
        public final String keyword;
        public final long createdAt;
        public final SearchManager.SearchResultData data;

        Session(String sessionId, String ruleName, String keyword,
                SearchManager.SearchResultData data, long createdAt) {
            this.sessionId = sessionId;
            this.ruleName = ruleName;
            this.keyword = keyword;
            this.data = data;
            this.createdAt = createdAt;
        }
    }
}
