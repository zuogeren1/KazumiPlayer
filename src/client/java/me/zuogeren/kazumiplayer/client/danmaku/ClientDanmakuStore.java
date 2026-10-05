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
 * <p>池内分两条通道：即时通道（displayAtMs=0，下一帧必出队且只出一次，行程由渲染端本地 MonoClock 驱动）
 * 与片内通道（displayAtMs 已由入队侧应用 timeOffset，按时刻升序整片保留，游标指向下一条待出队项）。
 * 两条通道分离的动因：片内整片装载（数万条）后，若即时项仍插在同一有序序列里，
 * 游标会被「时刻为 0 的即时项」反复阻塞而退化为整池扫描。
 *
 * <p>片内条目不做销毁式出队：已播条目留在池内，故进度条回退后同一批弹幕可再次吐出。保留范围以游标为界——
 * 已播区间只保留最近 {@link #REWIND_WINDOW_MS} 毫秒（或最近 {@link #REWIND_MAX_ENTRIES} 条，谁先到算谁），
 * 更早的已播条目直接回收。容量优先级：池内条目数达 danmakuMaxEntries 时先丢最旧的已播条目
 * （回看窗口可被压缩），回看窗口清空后仍超限才放弃入队——整片按时刻升序装载，等价于丢弃时间轴靠后的弹幕。
 * 游标推进规则——正常前进时从游标起吐出 displayAtMs ≤ currentVideoTimeMs + {@link #EPSILON_MS} 的条目
 * 并推进游标；跳变（|Δ| &gt; {@link #SEEK_DETECT_MS}，含回退与前进大跳）时先把游标二分回置到
 * 「首个晚于 currentVideoTimeMs − ε 的条目」：回退使该时刻之后的弹幕重新吐出，前进大跳则不把被跳过的
 * 整段一次性涌出；回退超出已播保留范围时无条目可吐，属预期行为。
 *
 * <p>出队复杂度：两条通道均为 O(到期条数)，跳变帧额外一次 O(log n) 二分，无整池扫描——
 * 序不变式由入队侧二分插入维护（插入点落在已吐区间时游标随之后移，避免重复吐出）。
 *
 * <p>裁定落地：A) seek 自检在本类 pollDue 内完成——基线差超 {@link #SEEK_DETECT_MS} 即重置基线并把
 * 游标二分回置（渲染层场景态清理由两层各自的 |Δ| 守卫负责）；
 * C) 到期判定含 {@link #EPSILON_MS} 容差，暂停期调用方传冻结位置即天然零出队。
 *
 * <p>线程模型：入队来自网络 handler（经 enqueueWork 的主线程）与拉取线线程，出队来自渲染帧线程，
 * 两者共享同一有序池，故所有池操作在屏幕状态对象上互斥；片内整片装载按时刻升序推进，
 * 故入队超容（池内条目数 ≥ danmakuMaxEntries）时丢弃当前这条即等价于丢弃时间轴靠后的弹幕。
 */
public final class ClientDanmakuStore {

    /** 到期判定容差（毫秒）：displayAtMs ≤ currentVideoTimeMs + EPSILON 视为到期 */
    public static final long EPSILON_MS = 250;
    /** 即时项行程时长（毫秒），渲染端据此推进滚动动画 */
    public static final long INSTANT_DISPLAY_MS = 5000;
    /** seek 自检阈值（毫秒）：pollDue 基线差超过此值视为大跨度跳变 */
    public static final long SEEK_DETECT_MS = 1500;
    /** 回看保留窗口（毫秒）：已播区间只保留最近这段时间内的条目，更早的已播条目回收（进度条回退可重放） */
    public static final long REWIND_WINDOW_MS = 300_000L;
    /** 回看保留条数上限：已播区间最多保留这么多条，与时间窗口谁先到算谁 */
    public static final int REWIND_MAX_ENTRIES = 2000;
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
     * 避免依赖 List.sort 的稳定性）；片内整片保留但已播区间只留回看窗口，游标推进。
     */
    private static final class ScreenPool {

        /** 已丢弃前缀的物理回收阈值（条）：攒够一批再整体左移，均摊 O(1) */
        private static final int DROPPED_TRIM_THRESHOLD = 256;
        /** 汇总日志间隔（条）：首次详记，之后每这么多条回收/丢弃汇总一次 */
        private static final long LOG_EVERY_DROPS = 4096L;

        private record Indexed(long seq, DanmakuEntry entry) {}

        /** 片内通道：时刻升序整片保留（含回看窗口内的已播条目），[retainBase, cursor) 为保留的已播区间 */
        private final List<Indexed> timeline = new ArrayList<>();
        /** 即时通道：displayAtMs=0，按入队序先进先出，出队即销毁 */
        private final List<DanmakuEntry> instant = new ArrayList<>();
        private long seq;
        /** 下一条待出队的片内条目下标 */
        private int cursor;
        /** 已回收前缀边界：[0, retainBase) 为回看窗口之外、待物理回收的已播条目 */
        private int retainBase;
        private long lastVideoTimeMs = Long.MIN_VALUE;
        /** 已播回收计数（窗口回收 + 容量压力回收） */
        private long playedRecycled;
        /** 尾部丢弃计数（已播区间清空后仍超限，放弃当前入队条目） */
        private long tailDropped;
        /** 汇总日志只详记一次，其后按 {@link #LOG_EVERY_DROPS} 间隔汇总，防整批装载刷屏 */
        private boolean capLogged;

        synchronized void enqueue(DanmakuEntry entry, int cap) {
            if (entry.displayAtMs() <= 0) {
                instant.add(entry);
                return;
            }
            prunePlayed();
            // 容量压力：先丢最旧的已播条目（回看窗口可被压缩），窗口清空后仍超限才丢时间轴尾部
            boolean evicted = false;
            while (logicalSize() >= cap && cursor > retainBase) {
                retainBase++;
                playedRecycled++;
                evicted = true;
            }
            if (logicalSize() >= cap) {
                tailDropped++;
                logCapacity(cap, entry.displayAtMs());
                return;
            }
            if (evicted) logCapacity(cap, entry.displayAtMs());
            trimDroppedPrefix();
            Indexed indexed = new Indexed(seq++, entry);
            int idx = upperBound(entry.displayAtMs(), indexed.seq());
            // 插入点落在已吐区间内（迟到的过期条目）：游标随插入右移，保证已吐条目不被重复吐出
            if (idx < cursor) cursor++;
            timeline.add(idx, indexed);
        }

        /**
         * 回看窗口回收：已播区间只保留最近 {@link #REWIND_WINDOW_MS} 毫秒（或最近
         * {@link #REWIND_MAX_ENTRIES} 条，谁先到算谁）的已播条目，更早的直接丢弃（游标左侧前缀）。
         */
        private void prunePlayed() {
            if (cursor <= retainBase) return;
            if (lastVideoTimeMs != Long.MIN_VALUE) {
                long cutoff = lastVideoTimeMs - REWIND_WINDOW_MS;
                while (retainBase < cursor && timeline.get(retainBase).entry().displayAtMs() < cutoff) {
                    retainBase++;
                    playedRecycled++;
                }
            }
            if (cursor - retainBase > REWIND_MAX_ENTRIES) {
                int excess = cursor - retainBase - REWIND_MAX_ENTRIES;
                retainBase += excess;
                playedRecycled += excess;
            }
        }

        /** 物理回收已丢弃前缀：攒够一批再整体左移，均摊 O(1) */
        private void trimDroppedPrefix() {
            if (retainBase < DROPPED_TRIM_THRESHOLD) return;
            timeline.subList(0, retainBase).clear();
            cursor -= retainBase;
            retainBase = 0;
        }

        /** 容量/回看回收 DEBUG 汇总：首次详记，之后每 {@link #LOG_EVERY_DROPS} 条汇总一次 */
        private void logCapacity(int cap, long displayAtMs) {
            long total = playedRecycled + tailDropped;
            if (!capLogged || total % LOG_EVERY_DROPS == 0) {
                capLogged = true;
                KazumiLog.danmaku.debug(
                    "Danmaku pool summary: cap {}, played-recycled {}, tail-dropped {}, retained {}, displayAt={}",
                    cap, playedRecycled, tailDropped, timeline.size() - retainBase, displayAtMs);
            }
        }

        synchronized List<DanmakuEntry> pollDue(long currentVideoTimeMs) {
            boolean jumped = lastVideoTimeMs != Long.MIN_VALUE
                && Math.abs(currentVideoTimeMs - lastVideoTimeMs) > SEEK_DETECT_MS;
            lastVideoTimeMs = currentVideoTimeMs;

            // 跳变（回退或前进大跳）：游标二分回置到「首个晚于 time−ε 的条目」——回退后该时刻之后的
            // 弹幕重新吐出，前进大跳则跳过被跳过的整段；早于回看保留范围的回退无条目可吐
            if (jumped) cursor = Math.max(firstAfter(currentVideoTimeMs - EPSILON_MS), retainBase);

            List<DanmakuEntry> due = null;
            if (!instant.isEmpty()) {
                due = new ArrayList<>(instant);
                instant.clear();
            }
            while (cursor < timeline.size()) {
                DanmakuEntry entry = timeline.get(cursor).entry();
                if (entry.displayAtMs() > currentVideoTimeMs + EPSILON_MS) break;
                if (due == null) due = new ArrayList<>();
                due.add(entry);
                cursor++;
            }
            prunePlayed();
            trimDroppedPrefix();
            return due == null ? List.of() : due;
        }

        /** 二分定位首个 displayAtMs &gt; timeMs 的条目（跳变回置游标用，已播条目同样参与定位） */
        private int firstAfter(long timeMs) {
            int low = retainBase;
            int high = timeline.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (timeline.get(mid).entry().displayAtMs() <= timeMs) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        private int logicalSize() {
            return (timeline.size() - retainBase) + instant.size();
        }

        /** 二分定位首个「晚于 (displayAtMs, seq)」的下标，保证插入后序不变 */
        private int upperBound(long displayAtMs, long entrySeq) {
            int low = retainBase;
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
