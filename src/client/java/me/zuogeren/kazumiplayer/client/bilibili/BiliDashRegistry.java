package me.zuogeren.kazumiplayer.client.bilibili;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B 站 DASH 解析结果的内存注册表：把一次解析得到的视频流与音频流暂存于此，
 * 换取一个可以交给 WaterMedia 的自定义 URI（kazumibili://kz-&lt;序号&gt;）。
 *
 * B 站 1080P 以上只有 DASH，视频与音频是两条独立 m4s，而 WaterMedia 的 MRL 解析只认 URI，
 * 无从表达"这条 URI 的伴随音轨是另一条 URI"。所以由 {@link KazumiBiliPlatform} 在本进程内注册为平台，
 * 解析引擎把自定义 URI 回调给平台，平台再回到本表取回原始流地址。
 *
 * 注册的设计约束：解析结果是瞬态的（CDN 地址带时效签名），因此条目按容量上限淘汰最旧的一条，
 * 避免长时间观影（连播数十集）时无限增长；过期判定交由调用方按快照内的过期时间处理，
 * 表本身不做主动清理——清理线程引入的生命周期管理不值当。
 */
public final class BiliDashRegistry {

    /** 单条解析结果：DASH 视频流、音频流地址与分辨率（分辨率用于构造 DataQuality） */
    public record Snapshot(String videoUri, String audioUri, int width, int height, long expiresAtEpochSec) {}

    /** 表内条目：快照与它的过期时间（过期时间由链接方给出，见 PlatformData.expires） */
    public record Entry(String videoUri, String audioUri, int width, int height, long expiresAtEpochSec) {}

    /** 自定义 URI 的 scheme，供注册方拼 URI 与排查日志 */
    public static final String SCHEME = "kazumibili";

    /** ID 前缀，拼在自增序号之前 */
    private static final String ID_PREFIX = "kz-";

    /** 条目容量上限：超过则淘汰最早插入的一条 */
    private static final int MAX_ENTRIES = 32;

    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 插入顺序，用于超容量时淘汰最旧条目 */
    private static final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

    /** ID 序号：单调递增即可，不参与淘汰，无需回绕 */
    private static final AtomicLong sequence = new AtomicLong();

    private BiliDashRegistry() {}

    /**
     * 注册一次解析结果，返回可直接交给 WaterMedia 的自定义 URI 字符串。
     *
     * @param audioUri 为空或空白时返回 null——没有伴随音轨就没有 DASH 拼装的意义，
     *                 调用方据此退回普通单流播放
     */
    public static String register(String videoUri, String audioUri, int width, int height, long expiresAtEpochSec) {
        if (videoUri == null || videoUri.isBlank()) return null;
        if (audioUri == null || audioUri.isBlank()) return null;
        String id = ID_PREFIX + sequence.incrementAndGet();
        Entry entry = new Entry(videoUri, audioUri, width, height, expiresAtEpochSec);
        // 登记顺序、写入表与容量淘汰必须是一个整体：先写表后登记会让淘汰看不到自己的 id，
        // 先登记后写表又会让另一线程的淘汰取下尚不存在的表项
        synchronized (order) {
            entries.put(id, entry);
            order.add(id);
            while (entries.size() > MAX_ENTRIES) {
                String oldest = order.poll();
                if (oldest == null) break;
                entries.remove(oldest);
            }
        }
        return SCHEME + "://" + id;
    }

    /** 按 id（形如 {@code kz-1}）取回快照；未命中返回 null */
    public static Snapshot find(String id) {
        Entry entry = resolve(id);
        if (entry == null) return null;
        return new Snapshot(entry.videoUri(), entry.audioUri(), entry.width(), entry.height(), entry.expiresAtEpochSec());
    }

    /** 按 id 取回过期时间（Unix 纪元秒）；未命中返回 0 */
    public static long expiresAt(String id) {
        Entry entry = resolve(id);
        return entry == null ? 0L : entry.expiresAtEpochSec();
    }

    /** id 合法性校验与查表合为一处，避免两处各判一次前缀 */
    private static Entry resolve(String id) {
        if (id == null || !id.startsWith(ID_PREFIX)) return null;
        return entries.get(id);
    }
}
