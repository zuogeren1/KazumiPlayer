package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.command.KazumiCommand;
import me.zuogeren.kazumiplayer.network.PacketDispatcher;
import me.zuogeren.kazumiplayer.network.packet.PlayStopPacket;
import me.zuogeren.kazumiplayer.network.ServerPacketHandlers;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.screen.ScreenRemovalListeners;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * 服务端附加 mod：注册服务端专属逻辑（命令、同步组、C→S 包处理）。
 * 包含在服务端 jar 和客户端 jar 中（客户端进程运行集成服务端时同样需要）。
 */
@Mod(KazumiPlayerServer.MODID)
public class KazumiPlayerServer {
    public static final String MODID = "kazumiplayer_server";

    public KazumiPlayerServer(IEventBus modEventBus, ModContainer modContainer) {
        KazumiLog.general.info("KazumiPlayer server side initializing...");

        // 规则引擎、规则管理器、搜索管理器
        RuleEngine ruleEngine = new RuleEngine();
        RuleManager ruleManager = new RuleManager(FMLPaths.CONFIGDIR.get());
        ruleManager.loadAll();
        SearchManager searchManager = new SearchManager(ruleEngine);

        // 同步组管理（服务端权威状态）
        SyncGroupManager.init();
        NeoForge.EVENT_BUS.register(SyncGroupManager.get());
        // 屏幕方块被移除时：停止所有观看者的播放 + 清理同步组
        ScreenRemovalListeners.set((screenId, screenPos) -> {
            stopWatchersOnScreenRemoved(screenId, screenPos);
            SyncGroupManager.get().leaveByScreenId(screenId);
        });

        // 初始化命令系统
        KazumiCommand.init(ruleManager, searchManager);
        NeoForge.EVENT_BUS.register(KazumiCommand.class);

        // 注入 C→S 网络包处理器
        PacketDispatcher.setServerHandler(new ServerPacketHandlers());

        KazumiLog.general.info("KazumiPlayer server side initialized ({} rules loaded)", ruleManager.count());
    }

    /** 屏幕被破坏：向该屏幕的所有观看者广播 PlayStopPacket，停止本地播放（含声音） */
    private static void stopWatchersOnScreenRemoved(UUID screenId, BlockPos screenPos) {
        var group = SyncGroupManager.get().getGroup(screenId);
        if (group == null || group.players.isEmpty()) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int notified = 0;
        for (UUID pid : group.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            if (p != null) {
                PacketDistributor.sendToPlayer(p, new PlayStopPacket(screenPos));
                notified++;
            }
        }
        KazumiLog.sync.info("Screen removed at {}, stopped playback for {} watchers", screenPos, notified);
    }
}
