package me.zuogeren.kazumiplayer.server.danmaku;

import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.network.packet.DanmakuBroadcastPacket;
import me.zuogeren.kazumiplayer.network.packet.DanmakuMode;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 房间弹幕发送入口：观看者用原版聊天栏发言即为弹幕。
 * 服务端订阅 ServerChatEvent，不取消事件——聊天消息照常进入原版聊天栏全局可见，
 * 弹幕是同屏社交层的附加呈现；观看者互发聊天双重可见为有意决策。
 * 非法输入静默忽略：错误反馈会诱导重试形成反馈循环刷屏，且聊天场景无弹幕式反馈位。
 */
public class DanmakuChatListener {

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        // 1. 总开关：未开弹幕的服务器零开销路径
        if (!Config.CONFIG.danmakuEnabled.get()) return;

        // 2. 查组定位：成员资格由查组天然完成（非观看者的普通聊天在此短路）
        ServerPlayer sp = event.getPlayer();
        var g = SyncGroupManager.get().findGroupByPlayer(sp.getUUID());
        if (g == null) return;

        // 3. 待机组（videoUrl 空）：不在观看中不能发
        if (g.videoUrl == null || g.videoUrl.isEmpty()) {
            KazumiLog.danmaku.debug("Danmaku ignored: standby group at {}", g.screenPos);
            return;
        }

        // 4. 文本校验：trim 后空拒；长度沿用 Minecraft 聊天原生上限（超长在 vanilla 提交侧已被拦截）
        String text = event.getRawText();
        if (text == null || text.isBlank()) {
            KazumiLog.danmaku.debug("Danmaku ignored: blank text from {}", event.getUsername());
            return;
        }

        // 5. 构造广播（含发言者本人）+ 旁路入池
        DanmakuBroadcastPacket packet = new DanmakuBroadcastPacket(
            g.screenPos, g.screenId,
            g.livePositionMillis(), MonoClock.millis(),
            sp.getUUID(), event.getUsername(),
            text, 0xFFFFFF, DanmakuMode.SCROLL);
        int sent = 0;
        for (UUID pid : g.players) {
            ServerPlayer viewer = sp.level().getServer().getPlayerList().getPlayer(pid);
            if (viewer != null) {
                PacketDistributor.sendToPlayer(viewer, packet);
                sent++;
            }
        }
        if (sent > 0) {
            KazumiLog.danmaku.debug("Danmaku from {} at screen {} broadcast to {} viewers",
                event.getUsername(), g.screenPos, sent);
        }
        DanmakuRoomManager.get().append(g.screenId, new DanmakuRoomManager.Entry(
            text, 0xFFFFFF, DanmakuMode.SCROLL,
            sp.getUUID(), event.getUsername(),
            packet.positionMs(), packet.serverTimestamp()));
    }
}
