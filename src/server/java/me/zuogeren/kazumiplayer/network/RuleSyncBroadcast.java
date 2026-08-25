package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.network.packet.RuleSyncPacket;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 规则变更后的全量广播工具：命令路径、GUI 路径与目录热重载共用同一实现。
 * 空列表也要广播——客户端以收到的列表整体替换缓存，空包即清空缓存
 * （删除最后一个规则后客户端不得残留失效规则）。
 */
public final class RuleSyncBroadcast {

    private RuleSyncBroadcast() {}

    /** 向所有在线玩家推送当前已安装规则全量列表 */
    public static void broadcast(RuleManager ruleManager) {
        var rules = ruleManager.listAll();
        String json = JsonUtil.GSON.toJson(
            rules.stream().map(ruleManager::get).filter(r -> r != null).toList());
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, new RuleSyncPacket(json));
        }
    }
}
