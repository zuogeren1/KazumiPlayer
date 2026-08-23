package me.zuogeren.kazumiplayer.search;

/**
 * 规则搜索会话缓存: sessionId -> 全量搜索结果（含关键词、是否全规则、原始 SearchResultData）
 * 翻页直接从缓存读取，不重复请求源站（对齐 SearchSessionCache 对 bgm.tv 搜索的处理）。
 */
public class RuleSearchSessionCache {
    private final TimedCache<Session> sessions = new TimedCache<>("KazumiPlayer-RuleSessionCache-Cleaner", 10);

    /**
     * 创建规则搜索会话，返回 sessionId
     */
    public String createSession(String ruleName, String keyword, SearchManager.SearchResultData data) {
        return sessions.put(new Session(ruleName, keyword, data));
    }

    /**
     * 获取会话；不存在或已过期返回 null
     */
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void shutdown() {
        sessions.shutdown();
    }

    public static class Session {
        /** 指定规则名；null = 全部规则搜索 */
        public final String ruleName;
        public final String keyword;
        public final SearchManager.SearchResultData data;

        Session(String ruleName, String keyword, SearchManager.SearchResultData data) {
            this.ruleName = ruleName;
            this.keyword = keyword;
            this.data = data;
        }
    }
}
