package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 客户端弹幕统一数据入口（渲染线只依赖此接口；契约见 reference/plans/f10-danmaku-room-protocol.md §5.3）。
 *
 * <p>统一混合队列：房间即时项（displayAtMs=0，下一帧即出队，行程由渲染端本地 MonoClock 驱动）
 * 与 dandanplay 片内时间轴项（displayAtMs 由入队侧应用 timeOffset）同池按 displayAtMs 判定到期。
 * 全部方法主线程调用（网络 handler 经 enqueueWork 保证，渲染帧天然主线程）。
 *
 * <p>裁定落地：A) seek 自检在本类 pollDue 内完成——基线差超 {@link #SEEK_DETECT_MS} 即重置基线
 * 并压缩明显过期的片内残留（不做出队压制，涌出全量交由渲染线容量闸统一丢弃）；
 * C) 到期判定含 {@link #EPSILON_MS} 容差，暂停期调用方传冻结位置即天然零出队。
 */
public final class ClientDanmakuStore {

    /** 到期判定容差（毫秒）：displayAtMs ≤ currentVideoTimeMs + EPSILON 视为到期 */
    public static final long EPSILON_MS = 250;
    /** 即时项行程时长（毫秒），渲染端据此推进滚动动画 */
    public static final long INSTANT_DISPLAY_MS = 5000;
    /** seek 自检阈值（毫秒）：pollDue 基线差超过此值视为大跨度跳变 */
    public static final long SEEK_DETECT_MS = 1500;

    private static final Map<BlockPos, ConcurrentLinkedDeque<DanmakuEntry>> QUEUES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_VIDEO_TIME = new ConcurrentHashMap<>();

    private ClientDanmakuStore() {}

    /** 入队（主线程）。即时项 displayAtMs=0；片内项 displayAtMs 已由入队侧应用 timeOffset */
    public static void enqueue(BlockPos pos, DanmakuEntry entry) {
        if (pos == null || entry == null) return;
        queueOf(pos).addLast(entry);
    }

    /**
     * 渲染帧调用：返回该屏到期条目（seek 涌出时全量交出，容量闸归渲染线），
     * 内部完成懒过期与 seek 自检。暂停期调用方传冻结位置 → 片内残留天然零出队。
     */
    public static List<DanmakuEntry> pollDue(BlockPos pos, long currentVideoTimeMs) {
        ConcurrentLinkedDeque<DanmakuEntry> queue = QUEUES.get(pos);
        if (queue == null) return List.of();

        // 裁定 A：seek 自检——大跨度跳变时重置基线并压缩明显过期的片内残留（即时项 displayAtMs=0 不受影响）
        Long previous = LAST_VIDEO_TIME.put(pos, currentVideoTimeMs);
        if (previous != null && Math.abs(currentVideoTimeMs - previous) > SEEK_DETECT_MS) {
            Iterator<DanmakuEntry> it = queue.iterator();
            while (it.hasNext()) {
                DanmakuEntry entry = it.next();
                if (entry.displayAtMs() > 0 && entry.displayAtMs() < currentVideoTimeMs - EPSILON_MS) {
                    it.remove();
                }
            }
        }

        List<DanmakuEntry> due = null;
        Iterator<DanmakuEntry> it = queue.iterator();
        while (it.hasNext()) {
            DanmakuEntry entry = it.next();
            if (entry.displayAtMs() <= currentVideoTimeMs + EPSILON_MS) {
                it.remove();
                if (due == null) due = new ArrayList<>();
                due.add(entry);
            }
        }
        return due == null ? List.of() : due;
    }

    /** 换集/停止清空该屏全部条目（同时重置 seek 基线，避免旧片时间基准污染新集） */
    public static void clear(BlockPos pos) {
        QUEUES.remove(pos);
        LAST_VIDEO_TIME.remove(pos);
    }

    /** 断线/卸载世界时全量清空（运维辅助，不属于 §5.3 三方法契约） */
    public static void clearAll() {
        QUEUES.clear();
        LAST_VIDEO_TIME.clear();
    }

    private static ConcurrentLinkedDeque<DanmakuEntry> queueOf(BlockPos pos) {
        return QUEUES.computeIfAbsent(pos, p -> new ConcurrentLinkedDeque<>());
    }
}
