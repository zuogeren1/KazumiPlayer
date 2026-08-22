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
        int sep = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String name = sep >= 0 ? s.substring(sep + 1) : s;
        try {
            String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            if (!decoded.isBlank()) name = decoded;
        } catch (IllegalArgumentException ignored) {}
        name = name.trim();
        if (name.isEmpty()) return "视频" + fallbackIdx;
        if (name.length() > MAX_LABEL_LEN) {
            // 按 code point 截断，避免切出孤立代理字符
            name = name.substring(0, name.offsetByCodePoints(0, name.codePointCount(0, MAX_LABEL_LEN - 1))) + "…";
        }
        return name;
    }

    @Nullable
    private static Road parseRoad(String episodeData) {
        if (episodeData == null || episodeData.isEmpty()) return null;
        Road road = JsonUtil.parseFirstRoad(episodeData);
        return road != null && QUEUE_ROAD_NAME.equals(road.name()) ? road : null;
    }
}
