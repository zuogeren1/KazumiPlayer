package me.zuogeren.kazumiplayer.server.danmaku;

import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端房间弹幕旁路缓冲池（审计用：dump/日志/未来回放窗口）。
 * 分发主路径不经过池——收到即校验转发，把「池满丢消息」「池锁竞争」从热路径上摘除。
 * 房间随组走：删组点全仓仅 SyncGroupManager.leave() 组空分支与 leaveByScreenId() 两处，
 * 各挂一行 clear 即穷尽覆盖；仅内存不落 NBT。
 */
public final class DanmakuRoomManager {

    private static final DanmakuRoomManager INSTANCE = new DanmakuRoomManager();

    public static DanmakuRoomManager get() { return INSTANCE; }

    private DanmakuRoomManager() {}

    private final Map<UUID, ArrayDeque<Entry>> rooms = new ConcurrentHashMap<>();

    /**
     * @param text       聊天原文（不含名字）
     * @param senderName 发送者名
     */
    public record Entry(String text, int colorRgb, DanmakuMode mode,
                        UUID senderUuid, String senderName, long positionMs, long monoMs) {}

    /** 旁路入池；超容量丢最旧 */
    public void append(UUID screenId, Entry entry) {
        if (screenId == null || entry == null) return;
        int cap = Config.CONFIG.danmakuPoolCapacity.get();
        var room = rooms.computeIfAbsent(screenId, k -> new ArrayDeque<>());
        room.addLast(entry);
        while (room.size() > cap) {
            room.pollFirst();
        }
        me.zuogeren.kazumiplayer.util.KazumiLog.danmaku.debug(
            "Room pool append at {}: size {}/{}", screenId, room.size(), cap);
    }

    /** 删组点接线（SyncGroupManager 双删组点调用）；幂等 */
    public void clear(UUID screenId) {
        if (rooms.remove(screenId) != null) {
            me.zuogeren.kazumiplayer.util.KazumiLog.danmaku.debug("Room pool cleared: {}", screenId);
        }
    }
}
