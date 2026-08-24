package me.zuogeren.kazumiplayer.search;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import me.zuogeren.kazumiplayer.util.HttpUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bangumi (bgm.tv) API 搜索客户端
 * 移植自 Kazumi Dart lib/request/apis/bangumi_api.dart
 */
public class BangumiApi {
    private static final String API_DOMAIN = "https://api.bgm.tv";
    private static final String SEARCH_PATH = "/v0/search/subjects";
    /** 单页条数：该端点实测忽略 query 的 limit，每页恒返回 20 条，仅 offset 生效 */
    private static final int PAGE_SIZE = 20;
    /** 聚合拉取页数上限：热门关键词的 total 可达数千，防止全量拖拽（5 页 = 100 条） */
    private static final int MAX_PAGES = 5;
    // 类级复用：HttpClient 自带连接池，每次 new 会重建连接池且句柄需靠 GC 回收
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final com.google.gson.Gson GSON = new GsonBuilder().create();
    /**
     * 专用小线程池：页请求与聚合编排都跑在这里，绝不占用 ForkJoinPool.commonPool——
     * 聚合若在 commonPool 工作线程内阻塞等待同池子任务，低并行度时会自锁挂起。
     * 容量 ≥ MAX_PAGES：外层编排 + 各页并发互不排队。
     */
    private static final java.util.concurrent.ExecutorService PAGE_EXECUTOR =
        java.util.concurrent.Executors.newFixedThreadPool(MAX_PAGES + 1, r -> {
            Thread t = new Thread(r, "KazumiPlayer-Bangumi");
            t.setDaemon(true);
            return t;
        });

    public CompletableFuture<List<BangumiSubject>> search(String keyword) {
        return search(keyword, 20, 0);
    }

    /**
     * 单页搜索。query limit 实测被端点忽略（恒返回每页 20 条），参数保留仅为兼容既有调用。
     */
    public CompletableFuture<List<BangumiSubject>> search(String keyword, int limit, int offset) {
        return fetchPageAsync(keyword, offset).thenApply(Page::items);
    }

    /**
     * 聚合搜索：按页 offset 并行拉取直至 total 或页数上限，拼接为完整结果列表。
     * GUI 搜索结果不做翻页——由服务端聚合后整表下发，客户端列表滚动浏览全部条目。
     * 全程非阻塞组合（thenCompose/allOf/thenApply），不在任何工作线程内 join 等待同池子任务。
     */
    public CompletableFuture<List<BangumiSubject>> searchAll(String keyword) {
        return fetchPageAsync(keyword, 0).thenCompose(first -> {
            int want = Math.min(first.total(), MAX_PAGES * PAGE_SIZE);
            if (want <= PAGE_SIZE) {
                return CompletableFuture.completedFuture(first.items());
            }
            List<CompletableFuture<Page>> rest = new ArrayList<>();
            for (int offset = PAGE_SIZE; offset < want; offset += PAGE_SIZE) {
                final int pageOffset = offset;
                rest.add(fetchPageAsync(keyword, pageOffset));
            }
            return CompletableFuture.allOf(rest.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<BangumiSubject> all = new ArrayList<>(first.items());
                    for (var f : rest) all.addAll(f.join().items()); // allOf 完成后必已完成，仅取值
                    return all;
                });
        });
    }

    /** 单页结果：items 为本页条目，total 为服务端报告的命中总数（聚合的终止依据） */
    private record Page(List<BangumiSubject> items, int total) {}

    /** 单页异步请求（专用执行器，避免占用 commonPool） */
    private CompletableFuture<Page> fetchPageAsync(String keyword, int offset) {
        return CompletableFuture.supplyAsync(() -> fetchPage(keyword, offset), PAGE_EXECUTOR);
    }

    private Page fetchPage(String keyword, int offset) {        try {
            String url = API_DOMAIN + SEARCH_PATH + "?limit=" + PAGE_SIZE + "&offset=" + offset;
            Map<String, Object> body = Map.of(
                "keyword", keyword,
                "sort", "heat",
                "filter", Map.of("type", List.of(2))
            );

            String jsonBody = GSON.toJson(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", HttpUtil.getRandomUserAgent())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                SearchResponse sr = GSON.fromJson(response.body(), SearchResponse.class);
                List<BangumiSubject> items = sr != null && sr.data != null ? sr.data : Collections.emptyList();
                return new Page(items, sr != null ? Math.max(sr.total, items.size()) : items.size());
            }
            KazumiLog.search.warn("Bangumi search returned {}", response.statusCode());
            throw new RuntimeException("bgm API 返回 " + response.statusCode());
        } catch (java.net.http.HttpTimeoutException e) {
            KazumiLog.search.warn("Bangumi search timeout: {}", e.getMessage());
            throw new RuntimeException("连接 api.bgm.tv 超时（当前网络不可达，可尝试配置代理）", e);
        } catch (Exception e) {
            KazumiLog.search.warn("Bangumi search failed: {}", e.getMessage());
            throw new RuntimeException("bgm 搜索失败: " + e.getMessage(), e);
        }
    }

    static class SearchResponse {
        List<BangumiSubject> data;
        int total;
    }

    public static class BangumiSubject {
        int id;
        String name;
        @SerializedName("name_cn")
        String nameCn;
        String summary;
        String date;
        Images images;

        public int getId() { return id; }
        public String getName() { return name; }
        public String getNameCn() { return nameCn; }
        public String getSummary() { return summary; }
        public String getDate() { return date; }
        public String getDisplayName() {
            return (nameCn != null && !nameCn.isEmpty()) ? nameCn : name;
        }
        public Images getImages() { return images; }
    }

    public static class Images {
        String large;
        public String getLarge() { return large; }
    }
}
