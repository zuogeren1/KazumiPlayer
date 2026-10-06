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
import java.util.Comparator;
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
 *   <li><b>直播间</b>：getRoomPlayInfo 取流（FLV 优先＝低延迟，无 FLV 时回落 http_hls 的 m3u8，ts 优先于 fmp4）。</li>
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
    private static final String DM_VIEW_API = "https://api.bilibili.com/x/v2/dm/web/view";
    private static final String DM_SEG_API = "https://api.bilibili.com/x/v2/dm/web/seg.so";
    private static final String LIVE_DANMU_INFO_API =
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo";
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
    /** 弹幕接口单次请求超时 */
    private static final int DM_HTTP_TIMEOUT_MS = 15_000;
    /** 分段拉取窗口：同一窗口内的分段并发请求数 */
    private static final int DM_SEGMENT_WINDOW = 4;
    /** dm/web/view 不可用时的段数上限兜底 */
    private static final int DM_SEGMENT_CAP_DEFAULT = 100;
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
     * {@code cid} 与 {@code liveRoomId} 供弹幕取数使用：视频页填分 P 的 cid（直播为 0），
     * 直播间填房间号（视频为 0）。
     */
    public record Stream(String url, String audioUrl, List<Quality> qualities, int currentQn,
                         long cid, long liveRoomId) {}

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
                        .thenCompose(dashBody -> buildDashStream(dashBody, cookie, cid)
                            .thenCompose(stream -> stream != null
                                ? CompletableFuture.completedFuture(stream)
                                : HttpUtil.fetch(playUrlApi(finalBvid, finalAid, cid, key, preferredQn, false),
                                        "GET", headers, Map.of()).thenApply(body2 -> buildSingleStream(body2, cid))));
                }));
    }

    /**
     * 从 DASH 响应构造可播放流：挑定档位的视频流 + 码率最高的音频流，
     * 两边各自的候选节点并行探测后取第一个可达的。
     * 空结果表示该响应没有可用 DASH，调用方回落单流。
     */
    private static CompletableFuture<Stream> buildDashStream(String body, String cookie, long cid) {
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
                qualitiesOf(data, videoByQn.keySet()), actualQn, cid, 0L));
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
    private static Stream buildSingleStream(String body, long cid) {
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
            data.has("quality") ? data.get("quality").getAsInt() : 0, cid, 0L);
    }

    /**
     * 直播流优选评分：**FLV 优先**（http_stream/flv）——FLV 无分段窗口，实测网页端同款协议延迟 1–3s；
     * HLS 的 m3u8 只有 3 段 × 3s 窗口（实测最新段起始距今 2–5s、窗口起点距今 9–10s），起播即落后约 9s，
     * 故只在响应里没有 FLV 时回落 HLS。同协议内 ts 优先于 fmp4、avc 优先于 hevc（HEVC 在部分环境无解码器）。
     */
    private static int liveStreamScore(String protocol, String formatName, String codecName) {
        int protocolScore = switch (protocol) {
            case "http_stream" -> 2;
            case "http_hls" -> 1;
            default -> 0;
        };
        int formatScore = switch (formatName) {
            case "flv", "ts" -> 2;
            case "fmp4" -> 1;
            default -> 0;
        };
        int codecScore = "avc".equals(codecName) ? 2 : ("hevc".equals(codecName) ? 1 : 0);
        return protocolScore * 100 + formatScore * 10 + codecScore;
    }

    /**
     * 解析直播间为可取流地址（默认 FLV，无 FLV 时回落 http_hls 的 m3u8；直播无时间轴，客户端按直播直连处理）。
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
            JsonObject chosen = null;
            int chosenScore = Integer.MIN_VALUE;
            JsonArray anyAcceptQn = null;
            for (int i = 0; i < streams.size(); i++) {
                JsonObject stream = streams.get(i).getAsJsonObject();
                String protocol = stream.has("protocol_name") ? stream.get("protocol_name").getAsString() : "";
                JsonArray formats = stream.getAsJsonArray("format");
                if (formats == null) continue;
                for (int j = 0; j < formats.size(); j++) {
                    JsonObject format = formats.get(j).getAsJsonObject();
                    String formatName = format.has("format_name") ? format.get("format_name").getAsString() : "";
                    JsonArray codecs = format.getAsJsonArray("codec");
                    if (codecs == null) continue;
                    for (int k = 0; k < codecs.size(); k++) {
                        JsonObject codec = codecs.get(k).getAsJsonObject();
                        if (anyAcceptQn == null && codec.has("accept_qn")) {
                            anyAcceptQn = codec.getAsJsonArray("accept_qn");
                        }
                        int score = liveStreamScore(protocol, formatName,
                            codec.has("codec_name") ? codec.get("codec_name").getAsString() : "");
                        if (score > chosenScore) {
                            chosenScore = score;
                            chosen = codec;
                        }
                    }
                }
            }
            if (chosen == null) throw new IllegalStateException("直播间未找到可用流");
            JsonArray acceptQn = chosen.has("accept_qn")
                ? chosen.getAsJsonArray("accept_qn") : anyAcceptQn;
            String url = streamUrlOf(chosen);
            List<Quality> qualities = new ArrayList<>();
            if (acceptQn != null) {
                for (int i = 0; i < acceptQn.size(); i++) {
                    int qn = acceptQn.get(i).getAsInt();
                    qualities.add(new Quality(qn, LIVE_QN_LABELS.getOrDefault(qn, "清晰度 " + qn), 0));
                }
            }
            int currentQn = chosen.has("current_qn") ? chosen.get("current_qn").getAsInt() : 0;
            return new Stream(url, "", qualities, currentQn, 0L, roomId);
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

    /**
     * 拉取视频片内弹幕全量：dm/web/view 取分段配置，seg.so 从第 1 段起按窗口推进
     * （窗口内并发 {@link #DM_SEGMENT_WINDOW} 个请求，整窗无数据即认为已到末尾——越界分段返回 304）。
     * 响应按 Content-Encoding 解包后手写 protobuf 解析，按弹幕 id 去重、过滤特殊弹幕池、
     * 按出现时间升序返回。任何失败都只记 DEBUG 日志并返回已取到的部分（空列表），不抛给调用方。
     */
    public static CompletableFuture<List<BilibiliDanmaku>> fetchVideoDanmaku(long cid, String cookie) {
        if (cid <= 0) {
            KazumiLog.danmaku.debug("[bilibili] video danmaku skipped: invalid cid {}", cid);
            return CompletableFuture.completedFuture(List.of());
        }
        if (cookie == null || cookie.isBlank()) {
            KazumiLog.danmaku.debug("[bilibili] video danmaku cid={} anonymous: only high-weight subset available", cid);
        }
        Map<String, String> headers = apiHeaders(REFERER, cookie);
        Map<String, BilibiliDanmaku> byId = new LinkedHashMap<>();
        return fetchDmSegmentCap(cid, headers)
            .thenCompose(cap -> fetchSegmentWindow(cid, headers, cap, 1, byId, 0, 0))
            .handle((ignored, error) -> {
                if (error != null) {
                    KazumiLog.danmaku.debug("[bilibili] video danmaku aborted cid={}: {}", cid, describe(error));
                }
                List<BilibiliDanmaku> list = new ArrayList<>(byId.values());
                list.sort(Comparator.comparingLong(BilibiliDanmaku::timeMs));
                KazumiLog.danmaku.debug("[bilibili] video danmaku cid={} -> {} entries", cid, list.size());
                return list;
            });
    }

    /**
     * 打开直播间实时弹幕会话：认证后 30s 心跳、解包 zlib、只回调 DANMU_MSG；
     * 掉线按 3s/6s/12s 退避重连（最多 3 次）。连接与重连在后台线程，回调不在主线程。
     * {@code cookie} 当前不参与握手：匿名 token 必须配 uid=0，带凭据反而要求 nav 的 mid 配对。
     */
    public static LiveDanmakuSession openLiveDanmaku(long roomId, String cookie, LiveDanmakuListener listener) {
        if (roomId <= 0) throw new IllegalArgumentException("直播间号无效");
        if (listener == null) throw new IllegalArgumentException("弹幕回调不能为空");
        return new LiveDanmakuConnection(roomId, cookie, listener);
    }

    /** 单个分段的取数结果：entries 为空且 failed=false 表示该段确实没有内容（越界/空段） */
    private record DmSegment(List<BilibiliDanmakuCodec.RawEntry> entries, boolean failed) {}

    /**
     * 取分段配置。接口给的 total 实测恒为段数上限（不是实际段数），真实段数由窗口探测决定，
     * 故这里只把上限作为推进边界，取不到时用默认上限，不阻断拉取。
     */
    private static CompletableFuture<Integer> fetchDmSegmentCap(long cid, Map<String, String> headers) {
        String url = DM_VIEW_API + "?type=1&oid=" + cid;
        return BilibiliHttp.get(url, headers, DM_HTTP_TIMEOUT_MS).handle((response, error) -> {
            if (error != null || response == null || !response.ok()) {
                KazumiLog.danmaku.debug("[bilibili] dm view unavailable cid={}: {}", cid,
                    error != null ? describe(error) : "HTTP " + (response == null ? 0 : response.status()));
                return DM_SEGMENT_CAP_DEFAULT;
            }
            try {
                BilibiliDanmakuCodec.ViewInfo info = BilibiliDanmakuCodec.parseView(response.body());
                KazumiLog.danmaku.debug("[bilibili] dm view cid={} pageSize={}ms segmentCap={} total={}",
                    cid, info.pageSizeMs(), info.segmentCap(), info.totalDanmaku());
                return info.segmentCap();
            } catch (RuntimeException e) {
                KazumiLog.danmaku.debug("[bilibili] dm view parse failed cid={}: {}", cid, e.getMessage());
                return DM_SEGMENT_CAP_DEFAULT;
            }
        });
    }

    /**
     * 逐窗拉取分段。越界分段实测有 HTTP 200 + 0 字节与 HTTP 304 两种表现，都按"该段无内容"处理。
     * 收束条件是连续两窗全空——一窗 4 段共 24 分钟，单个空窗可能只是这一段视频恰好没人发弹幕；
     * 整窗失败（网络/异常）时额外允许推进一窗，避免一次瞬时故障把后面的弹幕整段截掉。
     */
    private static CompletableFuture<Void> fetchSegmentWindow(long cid, Map<String, String> headers, int cap,
            int firstIndex, Map<String, BilibiliDanmaku> byId, int failureStreak, int emptyStreak) {
        int lastIndex = Math.min(cap, firstIndex + DM_SEGMENT_WINDOW - 1);
        List<CompletableFuture<DmSegment>> window = new ArrayList<>();
        for (int index = firstIndex; index <= lastIndex; index++) {
            window.add(fetchDmSegment(cid, headers, index));
        }
        return CompletableFuture.allOf(window.toArray(new CompletableFuture[0])).thenCompose(ignored -> {
            boolean present = false;
            boolean failed = false;
            for (CompletableFuture<DmSegment> future : window) {
                DmSegment segment = future.getNow(null);
                if (segment == null || segment.failed()) {
                    failed = true;
                    continue;
                }
                if (segment.entries().isEmpty()) continue;
                present = true;
                for (BilibiliDanmakuCodec.RawEntry entry : segment.entries()) {
                    byId.putIfAbsent(entry.id(), entry.danmaku());
                }
            }
            if (lastIndex >= cap) return CompletableFuture.completedFuture(null);
            if (present) return fetchSegmentWindow(cid, headers, cap, lastIndex + 1, byId, 0, 0);
            if (failed && failureStreak < 1) {
                return fetchSegmentWindow(cid, headers, cap, lastIndex + 1, byId, failureStreak + 1, emptyStreak);
            }
            if (emptyStreak < 1) {
                return fetchSegmentWindow(cid, headers, cap, lastIndex + 1, byId, 0, emptyStreak + 1);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private static CompletableFuture<DmSegment> fetchDmSegment(long cid, Map<String, String> headers, int index) {
        String url = DM_SEG_API + "?type=1&oid=" + cid + "&segment_index=" + index;
        return BilibiliHttp.get(url, headers, DM_HTTP_TIMEOUT_MS).handle((response, error) -> {
            if (error != null || response == null) {
                KazumiLog.danmaku.debug("[bilibili] danmaku segment {} failed: {}", index,
                    error != null ? describe(error) : "无响应");
                return new DmSegment(List.of(), true);
            }
            if (response.status() == 200 || response.status() == 304) {
                if (response.body().length == 0) return new DmSegment(List.of(), false);
                try {
                    DmSegment segment = new DmSegment(BilibiliDanmakuCodec.parseSegment(response.body()), false);
                    KazumiLog.danmaku.debug("[bilibili] danmaku segment {} -> {} bytes, {} entries",
                        index, response.body().length, segment.entries().size());
                    return segment;
                } catch (RuntimeException e) {
                    KazumiLog.danmaku.debug("[bilibili] danmaku segment {} parse failed: {}", index, e.getMessage());
                    return new DmSegment(List.of(), true);
                }
            }
            KazumiLog.danmaku.debug("[bilibili] danmaku segment {} -> HTTP {}", index, response.status());
            return new DmSegment(List.of(), true);
        });
    }

    /** 直播间弹幕接入信息：真实房间号、token 与 wss 订阅地址（按候选顺序排列） */
    record LiveDanmakuAccess(long roomId, String token, List<String> wssUrls) {}

    /**
     * 取直播间弹幕接入信息。getDanmuInfo 必须带 WBI 签名——缺签名时实测恒被风控拒为 code -352，
     * 故复用 {@link #sign} 与 {@link #deriveAndCacheMixinKey} 的现有实现，不另写一份签名。
     * 整条链路匿名：token 与认证 uid 必须同源，匿名 token 只能配 uid=0。
     */
    static CompletableFuture<LiveDanmakuAccess> fetchLiveDanmakuAccess(long roomId, String cookie) {
        return resolveLiveRoomId(roomId).thenCompose(realRoomId ->
            ensureMixinKeyAnonymously().thenCompose(key -> {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("id", String.valueOf(realRoomId));
                params.put("type", "0");
                params.put("web_location", "444.8");
                String url = LIVE_DANMU_INFO_API + "?" + sign(params, key);
                return BilibiliHttp.get(url, apiHeaders(LIVE_REFERER, ""), DM_HTTP_TIMEOUT_MS)
                    .thenApply(response -> {
                        if (!response.ok()) {
                            throw new IllegalStateException("直播弹幕信息失败：HTTP " + response.status());
                        }
                        return parseLiveDanmakuAccess(new String(response.body(), StandardCharsets.UTF_8),
                            realRoomId);
                    });
            }));
    }

    /**
     * 取真实房间号：短号（实测 room 6 → 7734200）直接拿去认证会失败，必须先经 get_info 映射；
     * 映射拿不到时回落原值（本就是真实房间号时该接口原样返回）。
     */
    private static CompletableFuture<Long> resolveLiveRoomId(long roomId) {
        String url = LIVE_ROOM_INFO_API + "?room_id=" + roomId;
        return BilibiliHttp.get(url, apiHeaders(LIVE_REFERER, ""), DM_HTTP_TIMEOUT_MS).handle((response, error) -> {
            if (error != null || response == null || !response.ok()) {
                KazumiLog.danmaku.debug("[bilibili] live room info unavailable for {}, using given id: {}", roomId,
                    error != null ? describe(error) : "HTTP " + (response == null ? 0 : response.status()));
                return roomId;
            }
            try {
                JsonObject data = dataOf(new String(response.body(), StandardCharsets.UTF_8), "直播间信息");
                if (data.has("room_id")) {
                    long real = data.get("room_id").getAsLong();
                    if (real > 0) {
                        if (real != roomId) {
                            KazumiLog.danmaku.debug("[bilibili] live room id mapped {} -> {}", roomId, real);
                        }
                        return real;
                    }
                }
            } catch (RuntimeException e) {
                KazumiLog.danmaku.debug("[bilibili] live room info parse failed for {}: {}", roomId, e.getMessage());
            }
            return roomId;
        });
    }

    private static LiveDanmakuAccess parseLiveDanmakuAccess(String body, long roomId) {
        JsonObject data = dataOf(body, "直播弹幕信息");
        String token = data.has("token") ? data.get("token").getAsString() : "";
        List<String> urls = new ArrayList<>();
        JsonArray hosts = data.getAsJsonArray("host_list");
        if (hosts != null) {
            for (int i = 0; i < hosts.size(); i++) {
                JsonObject host = hosts.get(i).getAsJsonObject();
                String name = host.has("host") ? host.get("host").getAsString() : "";
                if (name.isBlank()) continue;
                int port = host.has("wss_port") ? host.get("wss_port").getAsInt() : 0;
                String url = port > 0 ? "wss://" + name + ":" + port + "/sub" : "wss://" + name + "/sub";
                if (!urls.contains(url)) urls.add(url);
            }
        }
        // 443 对受限网络更友好（实测可用），作为末位候选
        String portFallback = "wss://broadcastlv.chat.bilibili.com:443/sub";
        if (!urls.contains(portFallback)) urls.add(portFallback);
        if (token.isBlank() || urls.isEmpty()) {
            throw new IllegalStateException("直播弹幕接口未返回 token 或节点");
        }
        return new LiveDanmakuAccess(roomId, token, urls);
    }

    /** 剥掉 CompletableFuture 的包装异常，取真实原因文本 */
    private static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
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
        String cached = cachedMixinKey();
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return HttpUtil.fetch(NAV_API, "GET", apiHeaders(referer, cookie), Map.of())
            .thenApply(BilibiliApi::deriveAndCacheMixinKey);
    }

    /**
     * 弹幕链路取 WBI 密钥：不经过 HttpUtil（它读 Mod 配置，弹幕链路需能在游戏外独立自测），
     * 且整条直播链路匿名——带 cookie 取到的 token 必须配 nav 的 mid 才能认证，匿名 token 配 uid=0 最稳。
     */
    static CompletableFuture<String> ensureMixinKeyAnonymously() {
        String cached = cachedMixinKey();
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return BilibiliHttp.get(NAV_API, apiHeaders(REFERER, ""), DM_HTTP_TIMEOUT_MS).handle((response, error) -> {
            if (error != null || response == null || !response.ok()) {
                throw new IllegalStateException("WBI 密钥获取失败："
                    + (error != null ? describe(error) : "HTTP " + (response == null ? 0 : response.status())));
            }
            return deriveAndCacheMixinKey(new String(response.body(), StandardCharsets.UTF_8));
        });
    }

    private static String cachedMixinKey() {
        String cached = mixinKey;
        return !cached.isEmpty() && System.currentTimeMillis() - mixinKeyAt < MIXIN_KEY_TTL_MS ? cached : null;
    }

    /**
     * 由 nav 响应派生密钥并写入缓存。nav 未登录时外层 code=-101，但 data.wbi_img 依然有效
     * （匿名请求同样需要 WBI 签名），因此这里不能走 dataOf 的 code 校验，只要求 wbi_img 存在。
     */
    private static String deriveAndCacheMixinKey(String body) {
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
