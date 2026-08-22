package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.network.packet.TimeSyncPacket;
import me.zuogeren.kazumiplayer.network.packet.TimeSyncResponsePacket;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端 ↔ 服务端时钟同步。
 *
 * 周期性发送探测包并按四时间戳中值法计算钟差 offset——
 * 加到本机 MonoClock 读数上即得服务器单调时间轴的当前值。
 * 播放位置插值（SyncStatePacket 锚点）依赖此 offset：
 * 没有它，两端系统时钟的固有偏差会直接变成播放位置误差，
 * 导致周期性漂移校正 seek 反复打断播放（"重复同步"卡顿）。
 */
public class ClientClockSync {

    /** 探测周期 (tick)：30 秒 @20tps */
    private static final int SYNC_INTERVAL_TICKS = 600;

    private static volatile long offsetMillis = Long.MIN_VALUE; // 未同步标记
    private static int tickCounter;

    /** 服务器单调时间轴当前毫秒；未完成同步时返回 Long.MIN_VALUE（调用方须跳过插值） */
    public static long serverNowMillis() {
        long off = offsetMillis;
        return off == Long.MIN_VALUE ? Long.MIN_VALUE : MonoClock.millis() + off;
    }

    public static boolean isSynced() {
        return offsetMillis != Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;
        if (++tickCounter % SYNC_INTERVAL_TICKS != 0) return;
        sendProbe();
    }

    /** 登录成功立即探测一次，尽快建立钟差 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // 连接刚建立即发；此时 connection 已可用
        sendProbe();
    }

    private static void sendProbe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new TimeSyncPacket(MonoClock.millis())));
        KazumiLog.sync.debug("clock sync probe sent");
    }

    /** 收到响应：四时间戳中值法计算服务器钟相对客户端钟的偏移 */
    public static void handleResponse(TimeSyncResponsePacket packet) {
        long clientRecv = MonoClock.millis();
        long serverMid = (packet.serverRecvMonotonicMs() + packet.serverSendMonotonicMs()) / 2;
        long clientMid = (packet.clientSendMonotonicMs() + clientRecv) / 2;
        offsetMillis = serverMid - clientMid;
        KazumiLog.sync.debug("clock synced, serverOffset={}ms", offsetMillis);
    }
}
