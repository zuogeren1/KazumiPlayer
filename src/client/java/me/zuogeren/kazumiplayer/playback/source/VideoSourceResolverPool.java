package me.zuogeren.kazumiplayer.playback.source;

import me.zuogeren.kazumiplayer.util.KazumiLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 视频源解析器租约池——对齐 Kazumi lib/services/video_source/video_source_resolver_pool.dart。
 *
 * 按 key（如屏幕坐标）发放租约：同 key 不并发解析；
 * worker 池按需扩张到 maxWorkers，空闲时回收。
 * 播放场景同一时刻通常只有一个解析在途，池化主要服务于多屏幕并行嗅探。
 */
public class VideoSourceResolverPool {

    private final List<Worker> workers = new ArrayList<>();
    private final Map<String, Lease> activeLeases = new HashMap<>();
    private int maxWorkers = 1;

    /** 调整池上限（蓝本 clamp 1..5） */
    public synchronized void resize(int maxWorkers) {
        this.maxWorkers = Math.max(1, Math.min(5, maxWorkers));
        trimIdleWorkers();
    }

    public synchronized Lease tryAcquire(String key) {
        if (activeLeases.containsKey(key)) return null;

        trimIdleWorkers();

        Worker worker = findIdleWorker();
        if (worker == null && workers.size() < maxWorkers) {
            worker = new Worker();
            workers.add(worker);
        }
        if (worker == null) return null;

        worker.busy = true;
        Lease lease = new Lease(key, worker);
        activeLeases.put(key, lease);
        return lease;
    }

    /**
     * 取消该屏在途解析并【立即回收租约】。
     * 解析 future 稍后以 Cancelled 完成，whenComplete 里的二次 release 因幂等检查直接跳过；
     * 若不在此处回收，后续 tryAcquire 会因 key 仍被占而失败——
     * 切集起播新 URL 时恰好撞上旧解析在途就会因此永久卡死（播放器已登记但永不播放）。
     *
     * @return true 表示确有在途解析被取消
     */
    public synchronized boolean cancel(String key) {
        Lease lease = activeLeases.get(key);
        if (lease == null) return false;
        lease.cancel();
        release(lease);
        return true;
    }

    public synchronized void cancelAll() {
        for (Lease lease : List.copyOf(activeLeases.values())) {
            lease.cancel();
        }
    }

    public synchronized void release(Lease lease) {
        if (activeLeases.get(lease.key) != lease) return;

        activeLeases.remove(lease.key);
        Worker worker = lease.worker;
        worker.busy = false;

        if (lease.shouldRetire || worker.retired) {
            worker.retire();
            workers.remove(worker);
            worker.dispose();
            return;
        }

        trimIdleWorkers();
    }

    public synchronized void dispose() {
        cancelAll();
        List<Worker> toDispose = List.copyOf(workers);
        activeLeases.clear();
        workers.clear();
        for (Worker worker : toDispose) {
            worker.dispose();
        }
    }

    private Worker findIdleWorker() {
        for (Worker worker : workers) {
            if (!worker.busy && !worker.retired) return worker;
        }
        return null;
    }

    private void trimIdleWorkers() {
        while (workers.size() > maxWorkers) {
            Worker idle = null;
            for (Worker worker : workers) {
                if (!worker.busy) {
                    idle = worker;
                    break;
                }
            }
            if (idle == null) return;

            idle.retire();
            workers.remove(idle);
            idle.dispose();
        }
    }

    // ---- 租约与 worker ----

    public static final class Lease {
        final String key;
        final Worker worker;
        private volatile boolean cancelled;
        private volatile boolean shouldRetire;

        Lease(String key, Worker worker) {
            this.key = key;
            this.worker = worker;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public CompletableFuture<VideoSource> resolve(
                String episodeUrl, boolean useLegacyParser, Duration timeout) {
            if (cancelled) {
                return CompletableFuture.failedFuture(new VideoSourceResolveException.Cancelled());
            }
            return worker.resolve(episodeUrl, useLegacyParser, timeout);
        }

        public void cancel() {
            cancelled = true;
            worker.cancel();
        }

        public void retire() {
            shouldRetire = true;
            worker.retire();
        }
    }

    private static final class Worker {
        private final RinkuVideoSourceService service = new RinkuVideoSourceService();
        private boolean busy;
        private boolean retired;

        CompletableFuture<VideoSource> resolve(
                String episodeUrl, boolean useLegacyParser, Duration timeout) {
            return service.resolve(episodeUrl, useLegacyParser, timeout);
        }

        void cancel() {
            service.cancel();
        }

        void retire() {
            retired = true;
        }

        void dispose() {
            KazumiLog.sniff.debug("[source] disposing resolver worker");
            service.dispose();
        }
    }
}
