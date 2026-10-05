package me.zuogeren.kazumiplayer.playback.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.zuogeren.kazumiplayer.client.BilibiliCredentials;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站视频解析（自研）：BV/av 页面 → cid → html5 播放接口 → 音视频合一的 mp4 直链。
 *
 * 不使用 WaterMedia 内置 BiliBiliPlatform 的视频解析结果：它产出 DASH 分离流（视频 m4s 与音频
 * slave 分属不同 CDN 节点），播放器为 slave 建连时不带平台头，CDN 终止握手 → 无音轨且画面卡缓冲。
 * html5 接口返回的 durl 是单一 mp4（音视频合一），且无需 Referer 即可拉流。
 *
 * 签名：WEB 接口需要 WBI 签名（w_rid + wts），密钥由 nav 接口的 wbi_img 派生，不需要登录。
 */
public final class BilibiliApi {

    private static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";
    private static final String VIEW_API = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAYURL_API = "https://api.bilibili.com/x/player/wbi/playurl";
    private static final String REFERER = "https://www.bilibili.com";

    /**
     * WBI 字符重排表（bilibili-api / bilibili-API-collect 的 mixinKeyEncTab 一致）。
     * 派生密钥只取重排结果的前 32 个字符，因此表的前 32 项有效即足够。
     */
    private static final int[] MIXIN_KEY_TAB = {
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1
    };

    private static final long MIXIN_KEY_TTL_MS = 30 * 60 * 1000L;
    private static final Pattern BV_PATTERN = Pattern.compile("/video/(BV[0-9A-Za-z]+)");
    private static final Pattern AV_PATTERN = Pattern.compile("/video/av(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");

    private static volatile String mixinKey = "";
    private static volatile long mixinKeyAt;

    private BilibiliApi() {}

    /** 清晰度档位：qn 为 B 站画质编号，label 为人读名（如「高清 720P」） */
    public record Quality(int qn, String label) {}

    /** 解析结果：可播放直链 + 可用档位 + 实际采用档位 */
    public record VideoStream(String url, java.util.List<Quality> qualities, int currentQn) {}

    /**
     * 解析视频页为可直接播放的 mp4 直链（支持 BV/av 与 ?p= 分 P）。
     * preferredQn &gt; 0 时按指定清晰度请求（越界由 B 站回落），否则用接口默认档。
     * 失败以异常收尾（接口变更/视频下架/区域限制等），调用方自行决定回退策略。
     */
    public static CompletableFuture<VideoStream> resolveVideo(String pageUrl, int preferredQn) {
        String bvid = null;
        String aid = null;
        Matcher bv = BV_PATTERN.matcher(pageUrl);
        if (bv.find()) {
            bvid = bv.group(1);
        } else {
            Matcher av = AV_PATTERN.matcher(pageUrl);
            if (av.find()) aid = av.group(1);
        }
        if (bvid == null && aid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("链接中未找到 BV/av 号"));
        }
        int page = 1;
        Matcher pm = PAGE_PATTERN.matcher(pageUrl);
        if (pm.find()) {
            try {
                page = Math.max(1, Integer.parseInt(pm.group(1)));
            } catch (NumberFormatException ignored) {}
        }

        final String finalBvid = bvid;
        final String finalAid = aid;
        final int finalPage = page;
        return ensureMixinKey().thenCompose(key -> {
            String viewQuery = finalBvid != null ? "bvid=" + finalBvid : "aid=" + finalAid;
            return HttpUtil.fetch(VIEW_API + "?" + viewQuery, "GET", apiHeaders(), Map.of())
                .thenCompose(body -> {
                    JsonObject data = dataOf(body, "视频信息");
                    long cid = cidOf(data, finalPage);
                    return HttpUtil.fetch(playUrlApi(finalBvid, finalAid, cid, key, preferredQn),
                        "GET", apiHeaders(), Map.of());
                });
        }).thenApply(body -> {
            JsonObject data = dataOf(body, "播放地址");
            JsonArray durl = data.getAsJsonArray("durl");
            if (durl == null || durl.isEmpty()) {
                throw new IllegalStateException("播放接口未返回 durl（可能是付费/区域限制内容）");
            }
            String url = durl.get(0).getAsJsonObject().get("url").getAsString();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("播放接口返回空地址");
            }
            return new VideoStream(url, qualitiesOf(data), data.has("quality") ? data.get("quality").getAsInt() : 0);
        });
    }

    /** 取第 page 个分 P 的 cid（page 从 1 开始；越界回落到首个分 P） */
    private static long cidOf(JsonObject data, int page) {
        JsonArray pages = data.getAsJsonArray("pages");
        if (pages != null && !pages.isEmpty()) {
            int idx = Math.min(page, pages.size()) - 1;
            JsonObject p = pages.get(idx).getAsJsonObject();
            if (p.has("cid")) return p.get("cid").getAsLong();
        }
        if (data.has("cid")) return data.get("cid").getAsLong();
        throw new IllegalStateException("视频信息缺少 cid");
    }

    /** 解析可用档位：accept_quality 与 accept_description 按下标对应 */
    private static java.util.List<Quality> qualitiesOf(JsonObject data) {
        java.util.List<Quality> list = new java.util.ArrayList<>();
        JsonArray qn = data.getAsJsonArray("accept_quality");
        JsonArray desc = data.getAsJsonArray("accept_description");
        if (qn == null) return list;
        for (int i = 0; i < qn.size(); i++) {
            int code = qn.get(i).getAsInt();
            String label = desc != null && i < desc.size() ? desc.get(i).getAsString() : String.valueOf(code);
            list.add(new Quality(code, label));
        }
        return list;
    }

    /** html5 播放接口（单流 mp4）：platform=html5 + high_quality=1 才会返回 durl */
    private static String playUrlApi(String bvid, String aid, long cid, String key, int preferredQn) {
        Map<String, String> params = new LinkedHashMap<>();
        if (bvid != null) params.put("bvid", bvid);
        if (aid != null) params.put("avid", aid);
        params.put("cid", String.valueOf(cid));
        params.put("qn", String.valueOf(preferredQn > 0 ? preferredQn : 127));
        params.put("fnval", "0");
        params.put("fnver", "0");
        params.put("fourk", "1");
        params.put("platform", "html5");
        params.put("high_quality", "1");
        params.put("from_client", "BROWSER");
        params.put("web_location", "1315873");
        return PLAYURL_API + "?" + sign(params, key);
    }

    /** WBI 签名：参数按 key 排序拼接后追加 wts 与 w_rid = md5(query + mixinKey) */
    private static String sign(Map<String, String> params, String key) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        if (!sorted.containsKey("web_location")) sorted.put("web_location", "1315873");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        String query = sb.toString();
        return query + "&w_rid=" + md5(query + key);
    }

    /** 派生并缓存 WBI 密钥（nav 接口的 wbi_img 文件名拼接后按表重排取前 32 位） */
    private static CompletableFuture<String> ensureMixinKey() {
        String cached = mixinKey;
        if (!cached.isEmpty() && System.currentTimeMillis() - mixinKeyAt < MIXIN_KEY_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return HttpUtil.fetch(NAV_API, "GET", apiHeaders(), Map.of()).thenApply(body -> {
            // nav 未登录时外层 code = -101，但 data.wbi_img 依然有效（匿名请求同样需要 WBI 签名），
            // 因此这里不能走 dataOf 的 code 校验，只要求 wbi_img 存在；其余接口仍按 code 判定
            JsonObject root = (body == null || body.isBlank()) ? null : JsonUtil.GSON.fromJson(body, JsonObject.class);
            JsonObject data = root == null ? null : root.getAsJsonObject("data");
            JsonObject wbi = data == null ? null : data.getAsJsonObject("wbi_img");
            if (wbi == null) {
                String detail = root != null && root.has("message")
                    ? root.get("message").getAsString() : "响应缺少 wbi_img";
                throw new IllegalStateException("nav 接口未返回 WBI 密钥：" + detail);
            }
            String imgKey = fileKey(wbi.get("img_url").getAsString());
            String subKey = fileKey(wbi.get("sub_url").getAsString());
            String combined = imgKey + subKey;
            StringBuilder sb = new StringBuilder();
            for (int i : MIXIN_KEY_TAB) {
                if (i < combined.length()) sb.append(combined.charAt(i));
            }
            String key = sb.length() <= 32 ? sb.toString() : sb.substring(0, 32);
            if (key.isEmpty()) throw new IllegalStateException("WBI 密钥派生失败");
            mixinKey = key;
            mixinKeyAt = System.currentTimeMillis();
            KazumiLog.sniff.debug("[bilibili] WBI mixin key refreshed");
            return key;
        });
    }

    private static String fileKey(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 解析响应外层 code/data；code != 0 时抛出带 message 的异常 */
    private static JsonObject dataOf(String body, String what) {
        if (body == null || body.isBlank()) throw new IllegalStateException(what + "响应为空");
        JsonObject root = JsonUtil.GSON.fromJson(body, JsonObject.class);
        if (root == null || !root.has("code")) throw new IllegalStateException(what + "响应无法解析");
        int code = root.get("code").getAsInt();
        if (code != 0) {
            String msg = root.has("message") ? root.get("message").getAsString() : String.valueOf(code);
            throw new IllegalStateException(what + "失败：" + msg + "（code " + code + "）");
        }
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new IllegalStateException(what + "响应缺少 data");
        return data;
    }

    /** 平台接口请求头：Referer 必带；已配置 Cookie 时附加登录态 */
    private static Map<String, String> apiHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        if (BilibiliCredentials.present()) {
            headers.put("Cookie", BilibiliCredentials.get());
        }
        return headers;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
