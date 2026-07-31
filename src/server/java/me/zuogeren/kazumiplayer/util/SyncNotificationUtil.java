package me.zuogeren.kazumiplayer.util;

import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 同步播放通知工具：向观看者发送操作提示
 */
public class SyncNotificationUtil {

    /** 通知其他观看者（不包括操作者本人） */
    public static void notifyOtherWatchers(ServerPlayer actor, BlockPos pos, UUID screenId, String action) {
        var g = SyncGroupManager.get().getGroup(screenId);
        if (g == null) return;
        String actorName = actor.getName().getString();
        Component msg = Component.literal("§e" + actorName + " " + action + " §7("
            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
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
        var g = SyncGroupManager.get().getGroup(screenId);
        if (g == null) return;
        Component msg = Component.literal("§e" + action + " §7("
            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
        var server = ((ServerLevel) contextPlayer.level()).getServer();
        for (UUID pid : g.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
