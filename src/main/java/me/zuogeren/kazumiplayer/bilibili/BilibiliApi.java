package me.zuogeren.kazumiplayer.bilibili;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站接口调用（common：服务端代理解析与客户端本端回落共用）。
 *
 * <ul>
 *   <li><b>视频页</b>：view 取 cid → WBI 签名调 html5 播放接口（fnval=0 + platform=html5 + high_quality=1）
 *       取 durl 单流 mp4（音视频合一）。不取 DASH：分离流的音频 slave 在播放器侧建连会被 CDN 终止
 *       （表现为无音轨且 demux 无数据推进、画面卡缓冲）。</li>
 *   <li><b>直播间</b>：getRoomPlayInfo 取 http_hls 的 m3u8（ts 优先，回落 fmp4）。</li>
 * </ul>
 *
 * 凭据以参数传入：服务端代理解析用服务端配置；客户端回落用本地配置。匿名调用亦可（清晰度上限 720P）。
 */
public final class BilibiliApi {

    private static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";
    private static final String VIEW_API = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAYURL_API = "https://api.bilibili.com/x/player/wbi/playurl";
    private static final String LIVE_PLAY_INFO_API =
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo";
    private static final String LIVE_ROOM_INFO_API =
            "https://api.live.bilibili.com/room/v1/Room/get_info";
    private static final String LIVE_ANCHOR_API =
            "https://api.live.bilibili.com/live_user/v1/UserInfo/get_anchor_in_room";
    private static final String REFERER = "https://www.bilibili.com";
    private static final String LIVE_REFERER = "https://live.bilibili.com";

    /**
     * WBI 字符重排表（与 bilibili-api / bilibili-API-collect 的 mixinKeyEncTab 一致）。
     * 派生密钥只取重排结果的前 32 个字符，故表的前 32 项有效即足够。
     */
    private static final int[] MIXIN_KEY_TAB = {
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1
    };

    /** 直播清晰度编号 → 人读名（直播接口只回 accept_qn 数字，无描述字段） */
    private static final Map<Integer, String> LIVE_QN_LABELS = Map.of(
        30000, "杜比",
        20000, "4K",
        10000, "原画",
        400, "蓝光",
        250, "超清",
        150, "高清",
        80, "流畅"
    );

    private static final long MIXIN_KEY_TTL_MS = 30 * 60 * 1000L;
    /** 大会员档位编号（1080P+ / 1080P60 / 4K / HDR / 杜比视界 / 8K）：接口未给上标时的兜底判定 */
    private static final Set<Integer> VIP_QNS = Set.of(112, 116, 120, 125, 126, 127);
    /** CDN 候选节点探测超时：候选并行探测，整体等待上限即此值 */
    private static final int PROBE_TIMEOUT_MS = 2_000;
    /** DASH 音频音质编号优先级（越靠前越好）：杜比、Hi-Res 之后是常规 AAC 三档 */
    private static final List<Integer> AUDIO_QN_PREFERENCE = List.of(30250, 30251, 30280, 30232, 30216);
    private static final Pattern BV_PATTERN = Pattern.compile("/video/(BV[0-9A-Za-z]+)");
    private static final Pattern AV_PATTERN = Pattern.compile("/video/av(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");
    private static final Pattern LIVE_ROOM_PATTERN = Pattern.compile("live\\.bilibili\\.com/(?:blanc/)?(\\d+)");

    private static volatile String mixinKey = "";
    private static volatile long mixinKeyAt;

    private BilibiliApi() {}

    /** 档位标记：该档需要大会员（接口上标指示，或编号落在会员档位段） */
    public static final int QUALITY_FLAG_VIP = 1;
    /** 档位标记：本账号当前拿不到该档（DASH 流里没有对应编号） */
    public static final int QUALITY_FLAG_UNAVAILABLE = 1 << 1;

    /** 清晰度档位：qn 为 B 站画质编号，label 为人读名（B 站返回的原文，不本地化） */
    public record Quality(int qn, String label, int flags) {
        public boolean vip() { return (flags & QUALITY_FLAG_VIP) != 0; }
        public boolean available() { return (flags & QUALITY_FLAG_UNAVAILABLE) == 0; }
    }

    /**
     * 解析结果。1080P 及以上只有 DASH，而 DASH 的音视频是两条独立流，
     * 此时 {@code audioUrl} 非空、必须挂成音频从属流（否则画面无声）；
     * 单流源（≤720P 的 mp4 与直播 m3u8）{@code audioUrl} 为空。
     */
    public record Stream(String url, String audioUrl, List<Quality> qualities, int currentQn) {}

    /** 页面元数据（队列显示用）：live 区分直播/视频，author 为 UP主或主播名 */
    public record Meta(boolean live, String author, String title) {}

    /**
     * 取页面元数据：视频 = UP主 + 标题（view 接口）；直播 = 主播名 + 直播间标题
     * （房间信息与主播信息分属两个接口，故并发请求后合并）。失败以异常收尾。
     */
    public static CompletableFuture<Meta> fetchMeta(String pageUrl, String cookie) {
        long roomId = liveRoomId(pageUrl);
        if (roomId > 0) {
            var roomFuture = HttpUtil.fetch(LIVE_ROOM_INFO_API + "?room_id=" + roomId, "GET",
                apiHeaders(LIVE_REFERER, cookie), Map.of());
            var anchorFuture = HttpUtil.fetch(LIVE_ANCHOR_API + "?roomid=" + roomId, "GET",
                apiHeaders(LIVE_REFERER, cookie), Map.of());
            return roomFuture.thenCombine(anchorFuture, (roomBody, anchorBody) -> {
                JsonObject room = dataOf(roomBody, "直播间信息");
                String title = room.has("title") ? room.get("title").getAsString() : "";
                String author = "";
                try {
                    JsonObject info = dataOf(anchorBody, "主播信息").getAsJsonObject("info");
                    if (info != null && info.has("uname")) author = info.get("uname").getAsString();
                } catch (Exception ignored) {
                    // 主播信息拿不到不影响标题展示
                }
                return new Meta(true, author, title);
            });
        }

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
        String query = bvid != null ? "bvid=" + bvid : "aid=" + aid;
        return HttpUtil.fetch(VIEW_API + "?" + query, "GET", apiHeaders(REFERER, cookie), Map.of())
            .thenApply(body -> {
                JsonObject data = dataOf(body, "视频信息");
                String title = data.has("title") ? data.get("title").getAsString() : "";
                String author = "";
                JsonObject owner = data.has("owner") ? data.getAsJsonObject("owner") : null;
                if (owner != null && owner.has("name")) author = owner.get("name").getAsString();
                return new Meta(false, author, title);
            });
    }

    /** 视频页是否可解析（BV/av） */
    public static boolean isVideoPage(String url) {
        return url != null && (BV_PATTERN.matcher(url).find() || AV_PATTERN.matcher(url).find());
    }

    /** 直播间号；非直播链接返回 -1 */
    public static long liveRoomId(String url) {
        if (url == null) return -1;
        Matcher m = LIVE_ROOM_PATTERN.matcher(url);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 解析视频页。1080P 及以上只有 DASH（音视频两条独立流），故优先走 DASH 参数；
     * DASH 拿不到时回落单流 mp4（音视频合一，上限 720P）。
     * preferredQn &gt; 0 时按指定清晰度请求（越界由 B 站回落）。
     */
    public static CompletableFuture<Stream> resolveVideo(String pageUrl, int preferredQn, String cookie) {
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
        final Map<String, String> headers = apiHeaders(REFERER, cookie);
        return ensureMixinKey(REFERER, cookie).thenCompose(key ->
            HttpUtil.fetch(VIEW_API + "?" + (finalBvid != null ? "bvid=" + finalBvid : "aid=" + finalAid),
                    "GET", headers, Map.of())
                .thenCompose(body -> {
                    long cid = cidOf(dataOf(body, "视频信息"), finalPage);
                    return HttpUtil.fetch(playUrlApi(finalBvid, finalAid, cid, key, preferredQn, true),
                            "GET", headers, Map.of())
                        .thenCompose(dashBody -> buildDashStream(dashBody, cookie)
                            .thenCompose(stream -> stream != null
                                ? CompletableFuture.completedFuture(stream)
                                : HttpUtil.fetch(playUrlApi(finalBvid, finalAid, cid, key, preferredQn, false),
                                        "GET", headers, Map.of()).thenApply(BilibiliApi::buildSingleStream)));
                }));
    }

    /**
     * 从 DASH 响应构造可播放流：挑定档位的视频流 + 码率最高的音频流，
     * 两边各自的候选节点并行探测后取第一个可达的。
     * 空结果表示该响应没有可用 DASH，调用方回落单流。
     */
    private static CompletableFuture<Stream> buildDashStream(String body, String cookie) {
        JsonObject data;
        JsonObject dash;
        java.util.NavigableMap<Integer, JsonObject> videoByQn;
        try {
            data = dataOf(body, "播放地址");
            dash = data.has("dash") ? data.getAsJsonObject("dash") : null;
            if (dash == null) return CompletableFuture.completedFuture(null);
            videoByQn = dashVideoByQn(dash);
        } catch (RuntimeException e) {
            KazumiLog.sniff.warn("[bilibili] DASH unavailable ({}), falling back to single stream", e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        if (videoByQn.isEmpty()) return CompletableFuture.completedFuture(null);

        int requested = data.has("quality") ? data.get("quality").getAsInt() : 0;
        java.util.Map.Entry<Integer, JsonObject> chosen = videoByQn.floorEntry(requested);
        if (chosen == null) chosen = videoByQn.firstEntry();
        int actualQn = chosen.getKey();

        JsonObject audio = pickDashAudio(dash);
        List<String> videoCandidates = candidatesOf(chosen.getValue());
        if (videoCandidates.isEmpty()) return CompletableFuture.completedFuture(null);
        List<String> audioCandidates = audio != null ? candidatesOf(audio) : List.of();

        Map<String, String> headers = apiHeaders(REFERER, cookie);
        return pickReachable(videoCandidates, headers).thenCombine(
            pickReachable(audioCandidates, headers),
            (videoUrl, audioUrl) -> new Stream(videoUrl, audioUrl,
                qualitiesOf(data, videoByQn.keySet()), actualQn));
    }

    /**
     * DASH 视频流按档位归并；同一档可能给多条编码（avc/hevc/av1），优先 avc——
     * HEVC/AV1 在部分环境缺少解码器，选了会黑屏。
     */
    private static java.util.NavigableMap<Integer, JsonObject> dashVideoByQn(JsonObject dash) {
        java.util.NavigableMap<Integer, JsonObject> byQn = new TreeMap<>();
        JsonArray videos = dash.getAsJsonArray("video");
        if (videos == null) return byQn;
        for (int i = 0; i < videos.size(); i++) {
            JsonObject video = videos.get(i).getAsJsonObject();
            if (!video.has("id")) continue;
            int qn = video.get("id").getAsInt();
            JsonObject previous = byQn.get(qn);
            if (previous == null || (!isAvc(previous) && isAvc(video))) byQn.put(qn, video);
        }
        return byQn;
    }

    private static boolean isAvc(JsonObject stream) {
        String codecs = stream.has("codecs") ? stream.get("codecs").getAsString() : "";
        return codecs.startsWith("avc");
    }

    /**
     * DASH 音频流：按音质编号优先（30280=192K，其次 30232、30216，会员音轨最前），
     * 编号都不认识时退回码率比较。数组顺序不稳定，故不能按下标取。
     */
    private static JsonObject pickDashAudio(JsonObject dash) {
        JsonArray audios = dash.getAsJsonArray("audio");
        if (audios == null || audios.isEmpty()) return null;
        JsonObject best = null;
        int bestRank = Integer.MAX_VALUE;
        int bestBandwidth = -1;
        for (int i = 0; i < audios.size(); i++) {
            JsonObject audio = audios.get(i).getAsJsonObject();
            int id = audio.has("id") ? audio.get("id").getAsInt() : -1;
            int rank = AUDIO_QN_PREFERENCE.indexOf(id);
            if (rank < 0) rank = AUDIO_QN_PREFERENCE.size();
            int bandwidth = audio.has("bandwidth") ? audio.get("bandwidth").getAsInt() : 0;
            if (rank < bestRank || (rank == bestRank && bandwidth > bestBandwidth)) {
                bestRank = rank;
                bestBandwidth = bandwidth;
                best = audio;
            }
        }
        return best;
    }

    /**
     * 一条流的候选地址。backupUrl 是官方 CDN（edge/upos），baseUrl 常是 P2P 节点
     * （*.mcdn.bilivideo.cn，实测约一成连接失败），故按 backup 优先、base 兜底排序，
     * 并把 P2P 节点整体压到最后。地址一律原样使用：跨主机搬参数、改写 host 都会 403/404。
     * html5 平台不返回 backupUrl，此时只有 baseUrl 一条。
     */
    private static List<String> candidatesOf(JsonObject stream) {
        List<String> ordered = new ArrayList<>();
        JsonArray backups = stream.getAsJsonArray("backupUrl");
        if (backups != null) {
            for (int i = 0; i < backups.size(); i++) ordered.add(backups.get(i).getAsString());
        }
        if (stream.has("baseUrl")) ordered.add(stream.get("baseUrl").getAsString());

        List<String> preferred = new ArrayList<>();
        List<String> p2p = new ArrayList<>();
        for (String url : ordered) {
            if (url == null || url.isBlank() || preferred.contains(url) || p2p.contains(url)) continue;
            String host;
            try {
                host = java.net.URI.create(url).getHost();
            } catch (RuntimeException e) {
                continue;
            }
            if (host != null && host.toLowerCase(java.util.Locale.ROOT).endsWith(".mcdn.bilivideo.cn")) {
                p2p.add(url);
            } else {
                preferred.add(url);
            }
        }
        preferred.addAll(p2p);
        return preferred;
    }

    /** 并行探测候选节点，返回第一个可达的；全部不可达时返回首个候选（交给播放器自己重试） */
    private static CompletableFuture<String> pickReachable(List<String> urls, Map<String, String> headers) {
        if (urls.isEmpty()) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Boolean>> probes = new ArrayList<>();
        for (String url : urls) {
            probes.add(HttpUtil.probeReadable(url, headers, PROBE_TIMEOUT_MS));
        }
        return CompletableFuture.allOf(probes.toArray(new CompletableFuture[0])).thenApply(ignored -> {
            for (int i = 0; i < urls.size(); i++) {
                if (probes.get(i).getNow(Boolean.FALSE)) {
                    KazumiLog.sniff.debug("[bilibili] picked CDN candidate #{}: {}", i, hostOf(urls.get(i)));
                    return urls.get(i);
                }
            }
            KazumiLog.sniff.warn("[bilibili] no CDN candidate reachable, using first: {}", hostOf(urls.get(0)));
            return urls.get(0);
        });
    }

    private static String hostOf(String url) {
        try {
            return String.valueOf(java.net.URI.create(url).getHost());
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** 单流回退路径（音视频合一的 mp4，上限 720P） */
    private static Stream buildSingleStream(String body) {
        JsonObject data = dataOf(body, "播放地址");
        JsonArray durl = data.getAsJsonArray("durl");
        if (durl == null || durl.isEmpty()) {
            throw new IllegalStateException("播放接口未返回 durl（可能是付费/区域限制内容）");
        }
        String url = durl.get(0).getAsJsonObject().get("url").getAsString();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("播放接口返回空地址");
        }
        return new Stream(url, "", qualitiesOf(data, null),
            data.has("quality") ? data.get("quality").getAsInt() : 0);
    }

    /**
     * 解析直播间为 http_hls 的 m3u8 地址（直播无时间轴，客户端按直播直连处理）。
     * preferredQn &gt; 0 时按指定清晰度请求（qn 参数，越界由 B 站回落）。
     */
    public static CompletableFuture<Stream> resolveLive(String roomUrl, int preferredQn, String cookie) {
        long roomId = liveRoomId(roomUrl);
        if (roomId <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("链接中未找到直播间号"));
        }
        String api = LIVE_PLAY_INFO_API
            + "?room_id=" + roomId
            + "&protocol=0,1&format=0,1,2&codec=0,1"
            + "&qn=" + (preferredQn > 0 ? preferredQn : 10000)
            + "&platform=web&ptype=8&dolby=5&panorama=1";
        return HttpUtil.fetch(api, "GET", apiHeaders(LIVE_REFERER, cookie), Map.of()).thenApply(body -> {
            JsonObject data = dataOf(body, "直播播放信息");
            JsonObject playurl = data.has("playurl_info")
                ? data.getAsJsonObject("playurl_info").getAsJsonObject("playurl") : null;
            if (playurl == null || !playurl.has("stream")) {
                throw new IllegalStateException("直播间未开播或未返回播放流");
            }
            JsonArray streams = playurl.getAsJsonArray("stream");
            JsonObject hlsCodec = null;
            JsonObject anyCodec = null;
            JsonArray acceptQn = null;
            for (int i = 0; i < streams.size(); i++) {
                JsonObject stream = streams.get(i).getAsJsonObject();
                String protocol = stream.has("protocol_name") ? stream.get("protocol_name").getAsString() : "";
                JsonArray formats = stream.getAsJsonArray("format");
                if (formats == null) continue;
                for (int j = 0; j < formats.size(); j++) {
                    JsonArray codecs = formats.get(j).getAsJsonObject().getAsJsonArray("codec");
                    if (codecs == null || codecs.isEmpty()) continue;
                    JsonObject codec = codecs.get(0).getAsJsonObject();
                    if (anyCodec == null) {
                        anyCodec = codec;
                        acceptQn = codec.getAsJsonArray("accept_qn");
                    }
                    // 优先 HLS（ts 优先于 fmp4），否则退回首个可用流
                    if ("http_hls".equals(protocol) && (hlsCodec == null || "ts".equals(
                            formats.get(j).getAsJsonObject().get("format_name").getAsString()))) {
                        hlsCodec = codec;
                        acceptQn = codec.getAsJsonArray("accept_qn");
                    }
                }
            }
            JsonObject chosen = hlsCodec != null ? hlsCodec : anyCodec;
            if (chosen == null) throw new IllegalStateException("直播间未找到可用流");
            String url = streamUrlOf(chosen);
            List<Quality> qualities = new ArrayList<>();
            if (acceptQn != null) {
                for (int i = 0; i < acceptQn.size(); i++) {
                    int qn = acceptQn.get(i).getAsInt();
                    qualities.add(new Quality(qn, LIVE_QN_LABELS.getOrDefault(qn, "清晰度 " + qn), 0));
                }
            }
            int currentQn = chosen.has("current_qn") ? chosen.get("current_qn").getAsInt() : 0;
            return new Stream(url, "", qualities, currentQn);
        });
    }

    /** 拼接直播流地址：host + base_url + extra */
    private static String streamUrlOf(JsonObject codec) {
        String base = codec.has("base_url") ? codec.get("base_url").getAsString() : "";
        JsonArray infos = codec.getAsJsonArray("url_info");
        if (infos == null || infos.isEmpty() || base.isBlank()) {
            throw new IllegalStateException("直播流地址字段缺失");
        }
        JsonObject info = infos.get(0).getAsJsonObject();
        String host = info.has("host") ? info.get("host").getAsString() : "";
        String extra = info.has("extra") ? info.get("extra").getAsString() : "";
        return host + base + extra;
    }

    /** 档位表编码："qn|flags|label;..."（label 可能含冒号，故用竖线分隔字段） */
    public static String encodeQualities(List<Quality> qualities) {
        StringBuilder sb = new StringBuilder();
        for (Quality q : qualities) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(q.qn()).append('|').append(q.flags()).append('|').append(q.label());
        }
        return sb.toString();
    }

    /** 档位表解码；畸形项跳过 */
    public static List<Quality> decodeQualities(String encoded) {
        List<Quality> list = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return list;
        for (String part : encoded.split(";")) {
            int first = part.indexOf('|');
            int second = first < 0 ? -1 : part.indexOf('|', first + 1);
            if (first <= 0 || second < 0) continue;
            try {
                list.add(new Quality(Integer.parseInt(part.substring(0, first)),
                    part.substring(second + 1),
                    Integer.parseInt(part.substring(first + 1, second))));
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }

    /**
     * 解析档位表：优先 support_formats（带 superscript，可识别"大会员"上标），
     * 缺失时回落 accept_quality/accept_description 按下标对应。
     *
     * @param availableIds DASH 流里实际给出的档位编号；为空表示无从判断可用性（单流回退路径）
     */
    private static List<Quality> qualitiesOf(JsonObject data, Set<Integer> availableIds) {
        List<Quality> list = new ArrayList<>();
        JsonArray formats = data.getAsJsonArray("support_formats");
        if (formats != null && !formats.isEmpty()) {
            for (int i = 0; i < formats.size(); i++) {
                JsonObject format = formats.get(i).getAsJsonObject();
                if (!format.has("quality")) continue;
                int qn = format.get("quality").getAsInt();
                String label = format.has("new_description") ? format.get("new_description").getAsString()
                    : format.has("display_desc") ? format.get("display_desc").getAsString() : String.valueOf(qn);
                // limit_watch_reason=1 表示本账号权益不足（大会员档位、或未登录）：
                // 实测它恒与 dash 实际下发的档位吻合，比按编号段猜测可靠
                boolean limited = format.has("limit_watch_reason")
                    && format.get("limit_watch_reason").getAsInt() == 1;
                list.add(new Quality(qn, label, flagsOf(qn, limited, availableIds)));
            }
            if (!list.isEmpty()) return list;
        }
        JsonArray qnArray = data.getAsJsonArray("accept_quality");
        JsonArray descArray = data.getAsJsonArray("accept_description");
        if (qnArray == null) return list;
        for (int i = 0; i < qnArray.size(); i++) {
            int qn = qnArray.get(i).getAsInt();
            String label = descArray != null && i < descArray.size()
                ? descArray.get(i).getAsString() : String.valueOf(qn);
            list.add(new Quality(qn, label, flagsOf(qn, false, availableIds)));
        }
        return list;
    }

    /**
     * 档位标记。权益受限优先采信接口的 limit_watch_reason（单流路径没有该字段，
     * 退化为按会员档位编号判断）；可用性以 DASH 实际下发的档位编号为准。
     */
    private static int flagsOf(int qn, boolean limited, Set<Integer> availableIds) {
        int flags = 0;
        if (limited || VIP_QNS.contains(qn)) flags |= QUALITY_FLAG_VIP;
        if (availableIds != null && !availableIds.isEmpty() && !availableIds.contains(qn)) {
            flags |= QUALITY_FLAG_UNAVAILABLE;
        }
        return flags;
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

    /**
     * 播放接口地址。{@code dash=true} 用 fnval=16 取 DASH（1080P 起步，但音视频分离）；
     * {@code dash=false} 用 fnval=0 + platform=html5 取音视频合一的 mp4（上限 720P）。
     * 单流上限来自接口本身：实测各 platform 下请求 qn=80/116 一律只回 720P 的 durl。
     */
    private static String playUrlApi(String bvid, String aid, long cid, String key,
            int preferredQn, boolean dash) {
        Map<String, String> params = new LinkedHashMap<>();
        if (bvid != null) params.put("bvid", bvid);
        if (aid != null) params.put("avid", aid);
        params.put("cid", String.valueOf(cid));
        params.put("qn", String.valueOf(preferredQn > 0 ? preferredQn : (dash ? 80 : 127)));
        params.put("fnval", dash ? "16" : "0");
        params.put("fnver", "0");
        params.put("fourk", "1");
        if (dash) {
            params.put("platform", "pc");
        } else {
            params.put("platform", "html5");
            params.put("high_quality", "1");
        }
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

    /** 派生并缓存 WBI 密钥（nav 的 wbi_img 文件名拼接后按表重排取前 32 位） */
    private static CompletableFuture<String> ensureMixinKey(String referer, String cookie) {
        String cached = mixinKey;
        if (!cached.isEmpty() && System.currentTimeMillis() - mixinKeyAt < MIXIN_KEY_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return HttpUtil.fetch(NAV_API, "GET", apiHeaders(referer, cookie), Map.of()).thenApply(body -> {
            // nav 未登录时外层 code=-101，但 data.wbi_img 依然有效（匿名请求同样需要 WBI 签名），
            // 因此这里不能走 dataOf 的 code 校验，只要求 wbi_img 存在；其余接口仍按 code 判定
            JsonObject root = (body == null || body.isBlank()) ? null : JsonUtil.GSON.fromJson(body, JsonObject.class);
            JsonObject data = root == null ? null : root.getAsJsonObject("data");
            JsonObject wbi = data == null ? null : data.getAsJsonObject("wbi_img");
            if (wbi == null) {
                String detail = root != null && root.has("message")
                    ? root.get("message").getAsString() : "响应缺少 wbi_img";
                throw new IllegalStateException("nav 接口未返回 WBI 密钥：" + detail);
            }
            String combined = fileKey(wbi.get("img_url").getAsString())
                + fileKey(wbi.get("sub_url").getAsString());
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

    /** 平台接口请求头：Referer 必带；有凭据时附加登录态 */
    private static Map<String, String> apiHeaders(String referer, String cookie) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", referer);
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
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
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
