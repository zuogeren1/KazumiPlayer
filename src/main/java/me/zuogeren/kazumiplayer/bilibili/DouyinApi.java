package me.zuogeren.kazumiplayer.bilibili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.zuogeren.kazumiplayer.util.DouyinUrls;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 抖音点播解析（纯 JDK HttpClient，零新增第三方依赖）。
 *
 * <p><b>为什么走 {@code api.amemv.com} 而不是网页接口</b>（2026-11 实测）：
 * <ul>
 *   <li>{@code www.douyin.com/aweme/v1/web/aweme/detail/} → 403 {@code Blocked by ArgusSecurityPlugin Uifid Not Found}</li>
 *   <li>{@code iesdouyin.com/web/api/v2/aweme/iteminfo/} → 200 但 body 0 字节</li>
 *   <li>分享页 {@code _ROUTER_DATA} → 72KB 空壳（{@code _$jsvmprt} JS VM 保护，无 videoInfoRes）</li>
 *   <li>{@code api.amemv.com/aweme/v1/aweme/detail/} → <b>200 + 完整 aweme_detail</b>（实测 130KB）</li>
 * </ul>
 * 直链本身还要 {@code Referer: https://www.douyin.com/}：缺它一律 403，带上即 206 video/mp4。
 *
 * <p><b>直播不支持</b>：{@code live.douyin.com/webcast/room/web/enter/} 实测 200 但 body 0 字节，
 * 需要 {@code ttwid} cookie + {@code a_bogus} 签名（JS VM 逆向），纯 HTTP 走不通。
 */
public final class DouyinApi {

    /** 详情接口：实测唯一还活着的点播入口 */
    private static final String DETAIL_ENDPOINT = "https://api.amemv.com/aweme/v1/aweme/detail/";

    /** 移动端 UA：详情接口按移动端身份返回完整字段，桌面 UA 会被风控 */
    private static final String MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; 2210132C Build/TKQ1.220829.002) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36";

    /** 直链防盗链 Referer：缺它 CDN 一律 403 */
    public static final String REFERER = "https://www.douyin.com/";

    private DouyinApi() {}

    /**
     * 一个可播档位。
     *
     * @param qn    档位编号（本类自定义：按码率降序 1..N，<b>1 为最高档</b>）
     * @param label 展示名（如「1080P · 2042 kbps」）
     * @param gear  抖音原始 gear_name（如 {@code normal_1080_0}），排查用
     * @param bitRate 码率（bps），0 表示未知
     */
    public record Quality(int qn, String label, String gear, long bitRate) {
        /**
         * 转成 GUI 下拉通用的档位类型：抖音没有大会员/权益受限概念，
         * 故 flags 恒 0（既非 vip 也非 unavailable），复用 B 站那条下拉链路。
         */
        public BilibiliApi.Quality toGuiQuality() {
            return new BilibiliApi.Quality(qn, label, 0);
        }
    }

    /**
     * 解析结果。
     *
     * @param videoUrl  选中的视频直链（已按 preferredQn 选档）
     * @param qualities 全部可选档位（按 qn 升序，qn=1 最高）
     * @param currentQn 本次实际选中的档位编号
     * @param title     作品标题
     * @param author    作者昵称
     * @param cover     封面图 URL（可能为空串）
     * @param durationMs 时长（毫秒），0 表示未知
     * @param awemeId   作品 ID
     */
    public record Stream(String videoUrl, List<Quality> qualities, int currentQn,
                         String title, String author, String cover, long durationMs, String awemeId) {}

    /**
     * 解析抖音作品页/分享页/短链为可播流。
     *
     * @param pageUrl     作品页 URL（短链需调用方先展开，见 {@link #expandShortLink}）
     * @param preferredQn 期望档位（1=最高）；≤0 表示取最高档
     */
    public static CompletableFuture<Stream> resolveVideo(String pageUrl, int preferredQn) {
        String awemeId = DouyinUrls.awemeIdOf(pageUrl);
        if (awemeId == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("链接中未找到抖音作品 ID"));
        }
        if (DouyinUrls.isNotePage(pageUrl)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("抖音图文作品没有视频流，暂不支持播放"));
        }
        String url = DETAIL_ENDPOINT + "?aweme_id=" + awemeId
            + "&aid=6383&device_platform=web&version_code=190500&version_name=19.5.0";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", MOBILE_UA);
        headers.put("Accept", "application/json, text/plain, */*");
        return HttpUtil.fetch(url, "GET", headers, Map.of())
            .thenApply(body -> parseDetail(body, awemeId, preferredQn));
    }

    /** 展开 v.douyin.com 短链为真实页面 URL */
    public static CompletableFuture<String> expandShortLink(String shortUrl) {
        return HttpUtil.resolveFinalUrl(shortUrl);
    }

    /** 解析详情接口响应；失败抛带可读原因的异常 */
    private static Stream parseDetail(String body, String awemeId, int preferredQn) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("抖音详情接口返回空响应");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("抖音详情接口响应不是合法 JSON");
        }
        int statusCode = optInt(root, "status_code", -1);
        if (statusCode != 0) {
            throw new IllegalStateException("抖音详情接口返回 status_code=" + statusCode);
        }
        JsonObject detail = optObject(root, "aweme_detail");
        if (detail == null) {
            // 作品被删/仅自己可见/地区限制都会走到这里，filter_detail 里有可读原因
            String reason = filterReason(root);
            throw new IllegalStateException(reason.isEmpty()
                ? "抖音未返回作品数据（可能已删除或不可见）" : reason);
        }
        String actualId = optString(detail, "aweme_id");
        if (!actualId.isEmpty() && !actualId.equals(awemeId)) {
            throw new IllegalStateException("抖音返回的作品 ID 与请求不一致");
        }

        JsonObject video = optObject(detail, "video");
        if (video == null) {
            throw new IllegalStateException("该抖音作品没有视频流（可能是图文作品）");
        }

        // 档位表：bit_rate 数组每项含 gear_name + bit_rate + play_addr.url_list
        List<RateEntry> rates = collectRates(video);
        if (rates.isEmpty()) {
            throw new IllegalStateException("抖音未返回任何可播档位");
        }
        // 按码率降序编号：qn=1 为最高档，与 B 站「qn 越大越清晰」相反，
        // 但抖音没有官方 qn 体系，用序号更直观（下拉里按 qn 升序展示即从高到低）
        rates.sort(Comparator.comparingLong(RateEntry::bitRate).reversed());
        List<Quality> qualities = new ArrayList<>();
        for (int i = 0; i < rates.size(); i++) {
            RateEntry r = rates.get(i);
            qualities.add(new Quality(i + 1, labelOf(r), r.gear(), r.bitRate()));
        }

        int wanted = preferredQn > 0 ? preferredQn : 1;
        RateEntry picked = rates.get(Math.max(0, Math.min(wanted - 1, rates.size() - 1)));

        JsonObject author = optObject(detail, "author");
        JsonObject cover = optObject(video, "origin_cover");
        String coverUrl = cover == null ? "" : firstUrl(cover);
        // 抖音 duration 字段实测是**毫秒**（如 663434 = 11 分钟），不是秒；
        // 但个别接口返回秒级小值，故按量级判断：小于 1000 视为秒并换算
        double durationRaw = optDouble(video, "duration", 0);
        long durationMs = durationRaw <= 0 ? 0
            : (durationRaw < 1000 ? (long) (durationRaw * 1000) : (long) durationRaw);

        KazumiLog.sniff.debug("[douyin] resolved aweme {} -> {} qualities, picked qn={} ({})",
            awemeId, qualities.size(), picked.qn(), picked.gear());

        return new Stream(picked.url(), List.copyOf(qualities), picked.qn(),
            optString(detail, "desc"), author == null ? "" : optString(author, "nickname"),
            coverUrl, durationMs, awemeId);
    }

    /** 一个档位条目：原始 gear + 码率 + 直链 + 是否 H.265（部分环境无解码器，展示时标注） */
    private record RateEntry(int qn, String gear, long bitRate, String url, boolean h265) {}

    /** 收集 bit_rate 数组里的全部档位（含 play_addr.url_list 的首个可用地址） */
    private static List<RateEntry> collectRates(JsonObject video) {
        List<RateEntry> out = new ArrayList<>();
        JsonArray arr = optArray(video, "bit_rate");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject rate = el.getAsJsonObject();
                JsonObject playAddr = optObject(rate, "play_addr");
                String url = playAddr == null ? "" : firstUrl(playAddr);
                if (url.isEmpty()) continue;
                out.add(new RateEntry(0, optString(rate, "gear_name"),
                    optLong(rate, "bit_rate", 0), url, optInt(rate, "is_h265", 0) == 1));
            }
        }
        if (out.isEmpty()) {
            // 兜底：bit_rate 缺失时用 play_addr 单流（老作品/部分接口返回）
            JsonObject playAddr = optObject(video, "play_addr");
            String url = playAddr == null ? "" : firstUrl(playAddr);
            if (!url.isEmpty()) {
                out.add(new RateEntry(0, "default", 0, url, false));
            }
        }
        return out;
    }

    /**
    /**
     * 由 gear_name 生成展示名。
     *
     * <p>gear_name 形如 {@code normal_1080_0} / {@code 1080_1_1} / {@code low_720_0}：
     * 中间段是短边分辨率，末段是同一分辨率下的码率档序号。**同一分辨率常有多个码率档**
     * （实测 1080P 有 4 档、540P 有 6 档），故必须带码率才能区分，否则下拉里会出现多个同名项。
     * 编码信息也带上：h265 档在部分环境无解码器，用户需要能看出来。
     */
    private static String labelOf(RateEntry r) {
        String gear = r.gear();
        String height = "";
        if (gear != null && !gear.isEmpty()) {
            for (String seg : gear.split("_")) {
                if (seg.matches("[0-9]{3,4}")) { height = seg; break; }
            }
        }
        String base = height.isEmpty() ? "原画" : height + "P";
        if (r.h265()) base += " H.265";
        if (r.bitRate() > 0) {
            return base + " · " + (r.bitRate() / 1000) + " kbps";
        }
        return base;
    }

    /** 取 url_list 里第一个非空地址 */
    private static String firstUrl(JsonObject holder) {
        JsonArray list = optArray(holder, "url_list");
        if (list == null) return "";
        for (JsonElement el : list) {
            if (el.isJsonPrimitive()) {
                String s = el.getAsString();
                if (s != null && !s.isBlank()) return s;
            }
        }
        return "";
    }

    /** 从 filter_detail 里取可读原因（作品被删/仅自己可见等） */
    private static String filterReason(JsonObject root) {
        JsonObject filter = optObject(root, "filter_detail");
        if (filter == null) return "";
        String msg = optString(filter, "detail_msg");
        if (!msg.isEmpty()) return msg;
        String reason = optString(filter, "filter_reason");
        return switch (reason) {
            case "status_self_see" -> "该作品仅作者自己可见";
            case "status_deleted" -> "该作品已被删除";
            case "status_friend_see" -> "该作品仅好友可见";
            case "core_dep" -> "该作品不存在或已被删除";
            default -> reason.isEmpty() ? "" : "该作品不可播放（" + reason + "）";
        };
    }

    // ---- Gson 安全取值（字段缺失/类型不符一律回落默认值，不抛异常） ----

    private static JsonObject optObject(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement el = o.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement el = o.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return "";
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int optInt(JsonObject o, String key, int def) {
        if (o == null) return def;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return def;
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static long optLong(JsonObject o, String key, long def) {
        if (o == null) return def;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return def;
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static double optDouble(JsonObject o, String key, double def) {
        if (o == null) return def;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return def;
        try {
            return el.getAsDouble();
        } catch (RuntimeException e) {
            return def;
        }
    }
}
