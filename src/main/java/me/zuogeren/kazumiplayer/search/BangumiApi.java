package me.zuogeren.kazumiplayer.search;

import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bangumi (bgm.tv) API 搜索客户端
 * 移植自 Kazumi Dart lib/request/apis/bangumi_api.dart
 */
public class BangumiApi {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API_DOMAIN = "https://api.bgm.tv";
    private static final String SEARCH_PATH = "/v0/search/subjects";

    public CompletableFuture<List<BangumiSubject>> search(String keyword) {
        return search(keyword, 20, 0);
    }

    public CompletableFuture<List<BangumiSubject>> search(String keyword, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_DOMAIN + SEARCH_PATH + "?limit=" + limit + "&offset=" + offset;
                Map<String, Object> body = Map.of(
                    "keyword", keyword,
                    "sort", "heat",
                    "filter", Map.of("type", List.of(2))
                );

                String jsonBody = new GsonBuilder().create().toJson(body);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", HttpUtil.getRandomUserAgent())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    SearchResponse sr = new GsonBuilder().create()
                        .fromJson(response.body(), SearchResponse.class);
                    return sr != null && sr.data != null ? sr.data : Collections.emptyList();
                }
                LOGGER.warn("Bangumi search returned {}", response.statusCode());
                return Collections.<BangumiSubject>emptyList();
            } catch (Exception e) {
                LOGGER.warn("Bangumi search failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    static class SearchResponse {
        List<BangumiSubject> data;
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
