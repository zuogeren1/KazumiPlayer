package me.zuogeren.kazumiplayer.playback.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 直链排队预检：频道目录型 m3u8（IPTV 合集，一个地址聚合多条频道流，
 * 如 raw.githubusercontent.com 上的 iptv-sources）不是单个可排队的媒体。
 * 预检确认目录后进一步解析出频道列表（标题+URL），供 GUI 展示为可 加/切 的频道条目；
 * 无法访问的链接同样在排队前拒收，而不是混进队列后在播放阶段才报错。
 *
 * 目录判定（与 WaterMedia MPEGTool.parse 的 Iptv 分支同构）：
 * #EXTM3U 开头 + 非 master（#EXT-X-STREAM-INF）+ 非媒体列表
 * （#EXT-X-TARGETDURATION/#EXT-X-MEDIA-SEQUENCE）+ 存在指向其他 .m3u8/.m3u 的条目行。
 */
public final class M3u8CatalogCheck {

    /** 探测请求超时；响应体读取上限；频道解析条数上限（防超大目录拖垮 GUI 列表） */
    private static final int CHECK_TIMEOUT_SECONDS = 10;
    private static final int MAX_CHECK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHANNELS = 500;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 频道目录中的一个频道 */
    public record Channel(String title, String url) {}

    /** 预检结论 + 目录型时的频道列表（其余结果为空列表） */
    public record ProbeResult(Result result, List<Channel> channels) {}

    public enum Result {
        /** 放行：可正常提交排队 */
        PLAYABLE,
        /** 频道目录型列表，已解析出频道清单 */
        CATALOG,
        /** 链接无法访问（超时/DNS/连接失败/HTTP ≥400），拒收 */
        UNREACHABLE
    }

    private M3u8CatalogCheck() {}

    /** 只有 .m3u8/.m3u 后缀（去 query/fragment）才需要内容级校验；其他 URL 直接放行 */
    public static boolean needsCheck(String url) {
        return pointsToPlaylist(url == null ? "" : url.trim());
    }

    /**
     * 异步预检并在回调线程给出结论：
     * PLAYABLE（放行）/ UNREACHABLE（链接不可达，拒收）/
     * CATALOG（频道目录，channels 为解析结果，供 GUI 展示）。
     * 回调方自行负责调度回主线程后再触碰 GUI/网络包。
     */
    public static void probeAsync(String url, Consumer<ProbeResult> onResult) {
        if (!needsCheck(url)) {
            onResult.accept(new ProbeResult(Result.PLAYABLE, List.of()));
            return;
        }
        Thread t = new Thread(() -> {
            String body = null;
            try {
                body = fetchText(url);
            } catch (Exception e) {
                onResult.accept(new ProbeResult(Result.UNREACHABLE, List.of())); // 超时/不可达：不入队
                return;
            }
            if (isCatalog(body)) {
                onResult.accept(new ProbeResult(Result.CATALOG, parseChannels(body, url)));
            } else {
                onResult.accept(new ProbeResult(Result.PLAYABLE, List.of()));
            }
        }, "KazumiPlayer-M3U8-Check");
        t.setDaemon(true);
        t.start();
    }

    /** 拉取列表文本（带 UA 与超时，超限截断——目录识别只需头部内容） */
    private static String fetchText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(CHECK_TIMEOUT_SECONDS))
                .header("User-Agent", UserAgents.getRandomUa())
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return new String(in.readNBytes(MAX_CHECK_BYTES), StandardCharsets.UTF_8);
        }
    }

    /**
     * 是否频道目录型列表：
     * <ul>
     *   <li>null / 非 #EXTM3U 开头 → 不是（HTML 错误页等交播放链路报错）</li>
     *   <li>#EXT-X-STREAM-INF → master 多画质，合法 → 不是</li>
     *   <li>#EXT-X-TARGETDURATION 或 #EXT-X-MEDIA-SEQUENCE → 单流媒体列表 → 不是</li>
     *   <li>以上皆无且存在指向其他 .m3u8/.m3u 的条目行 → 频道目录</li>
     * </ul>
     */
    static boolean isCatalog(String body) {
        if (body == null) return false;
        String text = body.stripLeading();
        if (text.startsWith("\uFEFF")) text = text.substring(1).stripLeading(); // BOM
        if (!text.startsWith("#EXTM3U")) return false;
        if (text.contains("#EXT-X-STREAM-INF")) return false;
        if (text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-MEDIA-SEQUENCE")) return false;
        for (String line : text.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (pointsToPlaylist(s)) return true;
        }
        return false;
    }

    /**
     * 解析目录条目：#EXTINF 行最后一个逗号后为标题，其后首个非注释行为其 URL
     * （相对路径按目录地址 resolve）；无 #EXTINF 前导的裸 URI 行以「未命名频道」兜底。
     */
    static List<Channel> parseChannels(String body, String baseUrl) {
        List<Channel> out = new ArrayList<>();
        if (body == null) return out;
        URI base = tryParse(baseUrl);
        String pendingTitle = null;
        for (String raw : body.split("\r?\n")) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            if (s.startsWith("#")) {
                if (s.startsWith("#EXTINF")) {
                    int comma = s.lastIndexOf(',');
                    String t = comma >= 0 ? s.substring(comma + 1).strip() : "";
                    pendingTitle = t.isEmpty() ? "未命名频道" : t;
                }
                continue;
            }
            pendingTitle = pendingTitle == null ? "未命名频道" : pendingTitle;
            String title = pendingTitle;
            pendingTitle = null;
            if (out.size() >= MAX_CHANNELS) break;
            out.add(new Channel(title, resolveUri(base, s)));
        }
        return out;
    }

    private static URI tryParse(String s) {
        try {
            return URI.create(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 条目 URL 规范化：非法字符宽松处理 + 相对路径按目录基址补全；无法解析时原样保留 */
    private static String resolveUri(URI base, String ref) {
        try {
            URI uri = base != null ? base.resolve(ref) : URI.create(ref);
            return uri.toString();
        } catch (Exception e) {
            return me.zuogeren.kazumiplayer.util.EpisodeUrlNormalizer.normalize(
                    base != null ? base.toString() : "", ref);
        }
    }

    /** 行/URL 去 query/fragment 后是否以 .m3u8/.m3u 结尾（忽略大小写） */
    private static boolean pointsToPlaylist(String line) {
        String lower = line.toLowerCase();
        int cut = lower.indexOf('?');
        if (cut >= 0) lower = lower.substring(0, cut);
        cut = lower.indexOf('#');
        if (cut >= 0) lower = lower.substring(0, cut);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }
}
