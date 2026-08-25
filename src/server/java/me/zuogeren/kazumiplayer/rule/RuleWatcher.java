package me.zuogeren.kazumiplayer.rule;

import me.zuogeren.kazumiplayer.network.RuleSyncBroadcast;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * 规则目录热重载监视器：监视 rules 目录，外部修改 plugins.json 后防抖自动重载并广播。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>防抖</b>：首个相关事件后静默 {@link #DEBOUNCE_MS} 再处理，合并编辑器连续写入与
 *       原子替换（tmp→target move 在 Windows 上表现为 DELETE+CREATE/MODIFY 事件簇）；</li>
 *   <li><b>自写回环抑制</b>：install/delete/update 等内部路径经 saveAll 写盘后已自带广播，
 *       静默窗 {@link #INTERNAL_WRITE_QUIET_MS} 内的回声事件直接忽略；</li>
 *   <li><b>失败安全</b>：解析失败由 {@link RuleManager#reloadLive()} 保留内存现状，仅记日志；
 *       OVERFLOW（事件缓冲溢出）按全量重载兜底；</li>
 *   <li><b>生命周期</b>：daemon 线程进程级存活——集成服每次开档 ensureStarted 幂等补启，
 *       专用服关闭随进程自然退出。</li>
 * </ul>
 */
public final class RuleWatcher {

    /** 相关事件后的防抖静默窗 */
    private static final long DEBOUNCE_MS = 800;
    /** 内部写盘后的回声抑制窗（须大于 saveAll 与事件派发的最大间隔） */
    private static final long INTERNAL_WRITE_QUIET_MS = 1500;

    private static volatile boolean running;
    private static WatchService watchService;

    private RuleWatcher() {}

    /** 启动目录监视（幂等）；目录不存在时先创建 */
    public static synchronized void ensureStarted(RuleManager ruleManager) {
        if (running) return;
        Path dir = ruleManager.rulesDirectory();
        try {
            Files.createDirectories(dir);
            WatchService ws = FileSystems.getDefault().newWatchService();
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            watchService = ws;
            running = true;
            Thread thread = new Thread(() -> watchLoop(ws, ruleManager), "KazumiPlayer-RuleWatcher");
            thread.setDaemon(true);
            thread.start();
            KazumiLog.rule.info("Rule directory watcher started: {}", dir.toAbsolutePath());
        } catch (IOException e) {
            KazumiLog.rule.error("Failed to start rule directory watcher, hot reload disabled", e);
        }
    }

    private static void watchLoop(WatchService ws, RuleManager ruleManager) {
        while (running) {
            WatchKey key;
            try {
                key = ws.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            if (key == null) continue;

            boolean relevant = false;
            boolean overflow = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    overflow = true;
                } else if (isPluginsFile(event)) {
                    relevant = true;
                }
            }
            key.reset();
            if (!relevant && !overflow) continue;

            // 防抖：吞掉窗口内的后续事件（原子替换的事件簇、编辑器多次落盘）
            try {
                Thread.sleep(DEBOUNCE_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                while ((key = ws.poll()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) overflow = true;
                        else if (isPluginsFile(event)) relevant = true;
                    }
                    key.reset();
                }
            } catch (ClosedWatchServiceException e) {
                return;
            }
            if (!relevant && !overflow) continue;

            // 自写回环抑制：内部安装/删除/更新路径已自带广播
            long sinceInternalWrite = MonoClock.millis() - ruleManager.lastInternalWriteAt();
            if (!overflow && sinceInternalWrite >= 0 && sinceInternalWrite < INTERNAL_WRITE_QUIET_MS) {
                KazumiLog.rule.debug("Skip auto reload: internal write echo ({}ms ago)", sinceInternalWrite);
                continue;
            }

            int count = ruleManager.reloadLive();
            if (count >= 0) {
                RuleSyncBroadcast.broadcast(ruleManager);
                KazumiLog.rule.info("Rules auto-reloaded from plugins.json: {} entries", count);
            } else {
                // reloadLive 已保留内存现状并记错误日志
                KazumiLog.rule.warn("Auto reload aborted, previous rules kept");
            }
        }
    }

    /** 事件目标是否为 plugins.json（.tmp 中转文件不触发） */
    private static boolean isPluginsFile(WatchEvent<?> event) {
        Object ctx = event.context();
        if (!(ctx instanceof Path p)) return false;
        Path fileName = p.getFileName();
        return fileName != null && "plugins.json".equals(fileName.toString());
    }
}
