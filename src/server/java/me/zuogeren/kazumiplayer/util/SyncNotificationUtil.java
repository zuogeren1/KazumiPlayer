package me.zuogeren.kazumiplayer.util;

import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 同步播放通知工具：向观看者发送操作提示（消息格式统一走 KazumiMessages）。
 * <p>
 * notifyOtherWatchers 为他人操作提醒（黄色 warn），broadcastToGroup 为中性广播通知（白色 info）。
 */
public class SyncNotificationUtil {

    /** 通知其他观看者（不包括操作者本人） */
    public static void notifyOtherWatchers(ServerPlayer actor, BlockPos pos, UUID screenId, String action) {
        notifyOtherWatchers(actor, pos, screenId, Component.literal(action));
    }

    /** 通知其他观看者（不包括操作者本人），可本地化组件 */
    public static void notifyOtherWatchers(ServerPlayer actor, BlockPos pos, UUID screenId, Component action) {
        var g = SyncGroupManager.get().getGroup(screenId);
        if (g == null) return;
        // 参数直传组件：getString 扁平化会在专用服上把翻译 key 原样发出（语言表只在客户端）
        Component msg = KazumiMessages.warnKeyNested("kazumiplayer.msg.notify_other",
                actor.getName(), action,
                Component.literal(String.valueOf(pos.getX())),
                Component.literal(String.valueOf(pos.getY())),
                Component.literal(String.valueOf(pos.getZ())));
        var server = ((ServerLevel) actor.level()).getServer();
        UUID actorId = actor.getUUID();
        for (UUID pid : g.players) {
            if (pid.equals(actorId)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    /** 通知组内所有玩家 */
    public static void broadcastToGroup(ServerPlayer contextPlayer, BlockPos pos, UUID screenId, String action) {
        broadcastToGroup(contextPlayer, pos, screenId, Component.literal(action));
    }

    /** 通知组内所有玩家，可本地化组件 */
    public static void broadcastToGroup(ServerPlayer contextPlayer, BlockPos pos, UUID screenId, Component action) {
        var g = SyncGroupManager.get().getGroup(screenId);
        if (g == null) return;
        Component msg = KazumiMessages.infoKeyNested("kazumiplayer.msg.broadcast_to_group",
                action,
                Component.literal(String.valueOf(pos.getX())),
                Component.literal(String.valueOf(pos.getY())),
                Component.literal(String.valueOf(pos.getZ())));
        var server = ((ServerLevel) contextPlayer.level()).getServer();
        for (UUID pid : g.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
