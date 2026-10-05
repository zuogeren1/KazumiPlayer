package me.zuogeren.kazumiplayer.util;

import me.zuogeren.kazumiplayer.rule.dto.Road;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 直链队列：复用 EpisodeData 存合成 Road（name = {@link #QUEUE_ROAD_NAME}），
 * roadIndex 恒为 0、episodeIndex（1-based）指向当前播放项——
 * 自动下一集与上一集/下一集沿用剧集列表的既有服务端链路在队列内推进，
 * BE NBT 经 markDirty 自动同步到客户端，无需新增网络包。
 */
public final class DirectLinkQueue {

    /** 合成 Road 的线路名，用于把队列数据与规则剧集数据区分开 */
    public static final String QUEUE_ROAD_NAME = "直链队列";
    /** 队列容量上限（含当前播放项） */
    public static final int MAX_SIZE = 100;
    private static final int MAX_LABEL_LEN = 24;

    private DirectLinkQueue() {}

    /** episodeData 是否为直链队列数据（而非规则剧集数据） */
    public static boolean isQueueData(String episodeData) {
        return parseRoad(episodeData) != null;
    }

    /**
     * 解析队列 URL 列表。
     *
     * @return 队列数据返回 URL 列表（可为空列表）；非队列数据或解析失败返回 null
     */
    public static List<String> parseUrls(String episodeData) {
        Road road = parseRoad(episodeData);
        return road != null ? road.data() : null;
    }

    /**
     * 解析队列显示名列表（与 {@link #parseUrls} 同序，取自 Road 的 identifier）。
     * 服务端 B 站元数据到位后会把 identifier 改写成「作者 · 标题」/「主播名 · 直播间标题」，
     * 因此展示方一律优先用这里的名称，只有缺失时才回落到 {@link #makeLabel} 按 URL 生成
     * （否则界面永远显示 BV/av 号与直播间房间号）。
     *
     * @return 非队列数据返回 null；identifier 缺失返回空列表
     */
    @Nullable
    public static List<String> parseLabels(String episodeData) {
        Road road = parseRoad(episodeData);
        if (road == null) return null;
        List<String> labels = road.identifier();
        return labels == null ? List.of() : labels;
    }

    /** 由 URL 列表构造队列 Road JSON，显示名自动生成 */
    public static String buildRoadJson(List<String> urls) {
        List<String> labels = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            labels.add(makeLabel(urls.get(i), i + 1));
        }
        return JsonUtil.GSON.toJson(List.of(new Road(QUEUE_ROAD_NAME, List.copyOf(urls), labels)));
    }

    /**
     * 队列项显示名：URL 去掉 query/fragment 后取末段路径并尝试解码，
     * 超长截断；取不到名称时回退「视频N」。
     */
    public static String makeLabel(String url, int fallbackIdx) {
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int f = s.indexOf('#');
        if (f >= 0) s = s.substring(0, f);
        // B 站视频页：末段路径为空（尾部斜杠）或不可读，直接以 BV/av 号作标签
        String biliId = bilibiliVideoId(s);
        int sep = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String name = biliId != null ? biliId : (sep >= 0 ? s.substring(sep + 1) : s);
        try {
            String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            if (!decoded.isBlank()) name = decoded;
        } catch (IllegalArgumentException ignored) {}
        name = name.trim();
        if (name.isEmpty()) return "视频" + fallbackIdx;
        return truncateLabel(name);
    }

    /** 标签统一截断：超长按 code point 截断（避免切出孤立代理字符） */
    public static String truncateLabel(String s) {
        if (s.length() <= MAX_LABEL_LEN) return s;
        return s.substring(0, s.offsetByCodePoints(0, s.codePointCount(0, MAX_LABEL_LEN - 1))) + "…";
    }

    /**
     * 由 B 站元数据生成队列显示名：「UP主 · 标题」/「主播名 · 直播间标题」；
     * 作者或标题缺失时退化为单项，皆空返回 null（调用方保留原标签）。
     */
    @Nullable
    public static String metaLabel(me.zuogeren.kazumiplayer.bilibili.BilibiliApi.Meta meta) {
        if (meta == null) return null;
        String author = meta.author() == null ? "" : meta.author().trim();
        String title = meta.title() == null ? "" : meta.title().trim();
        if (author.isEmpty() && title.isEmpty()) return null;
        if (author.isEmpty()) return truncateLabel(title);
        if (title.isEmpty()) return truncateLabel(author);
        return truncateLabel(author + " · " + title);
    }

    /**
     * 按 URL 替换队列项的显示名（B 站元数据异步补充用）。
     *
     * @return 新的 Road JSON；非队列数据、URL 不在队列中或标签无变化时原样返回
     */
    public static String withLabel(String roadJson, String url, String label) {
        Road road = parseRoad(roadJson);
        if (road == null || label == null) return roadJson;
        List<String> urls = road.data();
        List<String> labels = road.identifier();
        int idx = urls.indexOf(url);
        if (idx < 0 || idx >= labels.size() || label.equals(labels.get(idx))) return roadJson;
        List<String> updated = new ArrayList<>(labels);
        updated.set(idx, label);
        return JsonUtil.GSON.toJson(List.of(new Road(road.name(), List.copyOf(urls), List.copyOf(updated))));
    }

    /** B 站视频页 URL 中的 BV/av 号；非视频页返回 null（直播/短链沿用末段路径规则） */
    @Nullable
    private static String bilibiliVideoId(String urlWithoutQuery) {
        if (!BilibiliUrls.isVideoPage(urlWithoutQuery)) return null;
        int idx = urlWithoutQuery.toLowerCase().indexOf("/video/");
        if (idx < 0) return null;
        String rest = urlWithoutQuery.substring(idx + "/video/".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        String lower = rest.toLowerCase();
        return (lower.startsWith("bv") || lower.startsWith("av")) && rest.length() > 2 ? rest : null;
    }

    @Nullable
    private static Road parseRoad(String episodeData) {
        if (episodeData == null || episodeData.isEmpty()) return null;
        Road road = JsonUtil.parseFirstRoad(episodeData);
        return road != null && QUEUE_ROAD_NAME.equals(road.name()) ? road : null;
    }
}
