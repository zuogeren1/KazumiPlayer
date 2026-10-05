package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.DirectLinkQueue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队列项显示名的 B 站元数据缓存（「UP主 · 标题」/「主播名 · 直播间标题」）。
 *
 * <p>队列 Road 会在多处被整体重建——入队、切集把已播前缀移出队列
 * （{@link me.zuogeren.kazumiplayer.sync.PlaybackController#applyEpisodeSwitch}）、插队与移除——
 * 重建后显示名退回按 URL 生成的 BV/av 号或房间号，所以这些路径统一调用 {@link #applyTo} 回填。
 *
 * <p>本类只被服务端调用；写入 BE 会经 markDirty 自动同步给全组客户端。
 */
public final class BilibiliMetaCache {

    /** URL → 显示名 */
    private static final Map<String, String> LABELS = new ConcurrentHashMap<>();
    /** 已发起过元数据请求的 URL；请求失败时撤销，允许下次重试 */
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

    private BilibiliMetaCache() {}

    /** 已知显示名；尚未取到返回 null */
    public static String label(String url) {
        return url == null ? null : LABELS.get(url);
    }

    public static void put(String url, String label) {
        if (url != null && label != null) LABELS.put(url, label);
    }

    /** 登记一次元数据请求；同一 URL 已登记过返回 false（避免重复打扰接口） */
    public static boolean markRequested(String url) {
        return url != null && REQUESTED.add(url);
    }

    /** 请求失败时撤销登记，使后续操作可以重试一次 */
    public static void clearRequested(String url) {
        if (url != null) REQUESTED.remove(url);
    }

    /**
     * 用缓存里的显示名重写队列 Road（BE markDirty 自动同步全组）。
     *
     * @return 是否发生了改写
     */
    public static boolean applyTo(VideoScreenBlockEntity screen) {
        if (screen == null) return false;
        List<String> urls = DirectLinkQueue.parseUrls(screen.getEpisodeData());
        if (urls == null || urls.isEmpty()) return false;
        String json = screen.getEpisodeData();
        for (String url : urls) {
            String label = LABELS.get(url);
            if (label != null) json = DirectLinkQueue.withLabel(json, url, label);
        }
        if (json.equals(screen.getEpisodeData())) return false;
        screen.setEpisodeData(json);
        return true;
    }
}
