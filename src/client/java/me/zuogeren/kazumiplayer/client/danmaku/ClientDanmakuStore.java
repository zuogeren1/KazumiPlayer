package me.zuogeren.kazumiplayer.client.danmaku;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端弹幕统一数据入口（渲染线只依赖此接口；契约见 reference/plans/f10-danmaku-room-protocol.md §5.3）。
 *
 * <p>池内分两条通道：即时通道（displayAtMs=0，下一帧必出队，行程由渲染端本地 MonoClock 驱动）
 * 与片内通道（displayAtMs 已由入队侧应用 timeOffset，按时刻升序排列，头指针推进）。
 * 两条通道分离的动因：片内整片装载（数万条）后，若即时项仍插在同一有序序列里，
 * 头指针会被「时刻为 0 的即时项」反复阻塞而退化为整池扫描。
 *
 * <p>出队复杂度：即时通道 O(到期条数)，片内通道 O(到期条数 + 被压缩条数)，无整池扫描——
 * 头指针只在到期或 seek 压缩时前进，序不变式由入队侧二分插入维护。
 *
 * <p>裁定落地：A) seek 自检在本类 pollDue 内完成——基线差超 {@link #SEEK_DETECT_MS} 即重置基线
 * 并压缩明显过期的片内残留（不做出队压制，涌出全量交由渲染线容量闸统一丢弃）；
 * C) 到期判定含 {@link #EPSILON_MS} 容差，暂停期调用方传冻结位置即天然零出队。
 *
 * <p>线程模型：入队来自网络 handler（经 enqueueWork 的主线程）与拉取线线程，出队来自渲染帧线程，
 * 两者共享同一有序池，故所有池操作在屏幕状态对象上互斥；入队超容时丢弃时刻最靠后的片内条目。
 */
public final class ClientDanmakuStore {

    /** 到期判定容差（毫秒）：displayAtMs ≤ currentVideoTimeMs + EPSILON 视为到期 */
    public static final long EPSILON_MS = 250;
    /** 即时项行程时长（毫秒），渲染端据此推进滚动动画 */
    public static final long INSTANT_DISPLAY_MS = 5000;
    /** seek 自检阈值（毫秒）：pollDue 基线差超过此值视为大跨度跳变 */
    public static final long SEEK_DETECT_MS = 1500;
    /** 容量闸基准：即使 danmakuMaxEntries 配到下限，也至少保留这么多次帧消费水位 */
    public static final int MIN_POOL_ENTRIES = 512;

    /** 清屏回调：Store 判定该屏整批作废（换集/停止/断线）时通知渲染层释放缓存态 */
    public interface ClearListener {
        void onScreenCleared(BlockPos pos);
    }

    private static final Map<BlockPos, ScreenPool> POOLS = new ConcurrentHashMap<>();
    private static final List<ClearListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ClientDanmakuStore() {}

    /** 注册清屏回调（渲染层懒注册一次；重复注册由实现方自行幂等） */
    public static void addClearListener(ClearListener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    /** 入队（主线程）。即时项 displayAtMs=0；片内项 displayAtMs 已由入队侧应用 timeOffset */
    public static void enqueue(BlockPos pos, DanmakuEntry entry) {
        if (pos == null || entry == null) return;
        poolOf(pos).enqueue(entry, poolCap());
    }

    /** 批量入队（主线程）：片内时间轴整片装载，配合 {@link #clear} 使用（整批只读一次容量配置） */
    public static void enqueueAll(BlockPos pos, List<DanmakuEntry> entries) {
        if (pos == null || entries == null || entries.isEmpty()) return;
        ScreenPool pool = poolOf(pos);
        int cap = poolCap();
        for (DanmakuEntry entry : entries) {
            if (entry != null) pool.enqueue(entry, cap);
        }
    }

    /**
     * 渲染帧调用：返回该屏到期条目（seek 涌出时全量交出，容量闸归渲染线），
     * 内部完成懒过期与 seek 自检。暂停期调用方传冻结位置 → 片内残留天然零出队。
     *
     * <p>返回顺序：先即时项（入队序），再片内项（时刻升序），渲染线据此让社交弹幕优先上屏。
     */
    public static List<DanmakuEntry> pollDue(BlockPos pos, long currentVideoTimeMs) {
        if (pos == null) return List.of();
        ScreenPool pool = POOLS.get(pos);
        if (pool == null) return List.of();
        return pool.pollDue(currentVideoTimeMs);
    }

    /** 换集/停止清空该屏全部条目（同时重置 seek 基线并通知渲染层释放缓存态） */
    public static void clear(BlockPos pos) {
        if (pos == null) return;
        POOLS.remove(pos);
        notifyCleared(pos);
    }

    /** 断线/卸载世界时全量清空（运维辅助，不属于 §5.3 三方法契约） */
    public static void clearAll() {
        List<BlockPos> cleared = List.copyOf(POOLS.keySet());
        POOLS.clear();
        for (BlockPos pos : cleared) {
            notifyCleared(pos);
        }
    }

    private static ScreenPool poolOf(BlockPos pos) {
        return POOLS.computeIfAbsent(pos, p -> new ScreenPool());
    }

    /** 池容量上限：单点读取配置（批次内复用同一值，避免每条目都走一次 spec.get()） */
    private static int poolCap() {
        int configured = ClientConfig.CONFIG.danmakuMaxEntries.get();
        return Math.max(MIN_POOL_ENTRIES, configured);
    }

    private static void notifyCleared(BlockPos pos) {
        if (LISTENERS.isEmpty()) return;
        for (ClearListener listener : LISTENERS) {
            try {
                listener.onScreenCleared(pos);
            } catch (RuntimeException e) {
                KazumiLog.danmaku.debug("Danmaku clear listener failed at {}: {}", pos, e.toString());
            }
        }
    }

    /**
     * 单屏有序池。片内条目承载序号保证同时刻入队序稳定（Comparator 不比较相等键，
     * 避免依赖 List.sort 的稳定性）；容量超限丢弃时刻最靠后的一条。
     */
    private static final class ScreenPool {

        private record Indexed(long seq, DanmakuEntry entry) {}

        /** 片内通道：时刻升序，[compactBase, size) 为未出队有效区间 */
        private final List<Indexed> timeline = new ArrayList<>();
        /** 即时通道：displayAtMs=0，按入队序先进先出 */
        private final List<DanmakuEntry> instant = new ArrayList<>();
        private long seq;
        /** 已被压缩/出队物理移除的前缀长度（timeline 逻辑下标偏移） */
        private int compactBase;
        private long lastVideoTimeMs = Long.MIN_VALUE;
        private long droppedTotal;
        /** 容量溢出只详细记一次，防整批装载刷屏 */
        private boolean capLogged;

        synchronized void enqueue(DanmakuEntry entry, int cap) {
            if (entry.displayAtMs() <= 0) {
                instant.add(entry);
                return;
            }
            if (logicalSize() >= cap) {
                droppedTotal++;
                if (!capLogged) {
                    capLogged = true;
                    KazumiLog.danmaku.debug("Danmaku dropped at enqueue: cap {} reached, displayAt={}", cap,
                        entry.displayAtMs());
                } else if (droppedTotal % 4096 == 0) {
                    KazumiLog.danmaku.debug("Danmaku dropped at enqueue: total {} over cap {}", droppedTotal, cap);
                }
                return;
            }
            Indexed indexed = new Indexed(seq++, entry);
            int idx = upperBound(entry.displayAtMs(), indexed.seq());
            timeline.add(idx, indexed);
        }

        synchronized List<DanmakuEntry> pollDue(long currentVideoTimeMs) {
            boolean seeked = lastVideoTimeMs != Long.MIN_VALUE
                && Math.abs(currentVideoTimeMs - lastVideoTimeMs) > SEEK_DETECT_MS;
            lastVideoTimeMs = currentVideoTimeMs;

            List<DanmakuEntry> due = null;
            if (!instant.isEmpty()) {
                due = new ArrayList<>(instant);
                instant.clear();
            }
            int removed = 0;
            while (compactBase < timeline.size()) {
                DanmakuEntry entry = timeline.get(compactBase).entry();
                if (entry.displayAtMs() > currentVideoTimeMs + EPSILON_MS) break;
                if (due == null) due = new ArrayList<>();
                due.add(entry);
                compactBase++;
                removed++;
            }
            // 压缩落在出队之后：跳变帧该吐出的到期项先完整交出（裁定 D：不做出队压制），
            // 压缩只吃掉「既过期又没能交出」的残留，避免它们随新位置反复空转
            if (seeked) compactStale(currentVideoTimeMs - EPSILON_MS);
            if (removed > 0) trimPrefix();
            return due == null ? List.of() : due;
        }

        /** 压缩远落后于新位置的片内残留（seek 自检专用；即时通道不受影响） */
        private void compactStale(long cutoffMs) {
            while (compactBase < timeline.size()
                    && timeline.get(compactBase).entry().displayAtMs() < cutoffMs) {
                compactBase++;
            }
            trimPrefix();
        }

        /** 前缀物理回收：连续移除后整体左移，保证列表不无限增长 */
        private void trimPrefix() {
            if (compactBase == 0) return;
            if (compactBase >= timeline.size()) {
                timeline.clear();
                compactBase = 0;
                return;
            }
            if (compactBase < 512 && compactBase * 4 < timeline.size()) return;
            timeline.subList(0, compactBase).clear();
            compactBase = 0;
        }

        private int logicalSize() {
            return (timeline.size() - compactBase) + instant.size();
        }

        /** 二分定位首个「晚于 (displayAtMs, seq)」的下标，保证插入后序不变 */
        private int upperBound(long displayAtMs, long entrySeq) {
            int low = compactBase;
            int high = timeline.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                Indexed probe = timeline.get(mid);
                boolean probeNotLater = probe.entry().displayAtMs() < displayAtMs
                    || (probe.entry().displayAtMs() == displayAtMs && probe.seq() <= entrySeq);
                if (probeNotLater) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }
}
