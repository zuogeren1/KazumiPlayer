package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.command.KazumiCommand;
import me.zuogeren.kazumiplayer.network.PacketDispatcher;
import me.zuogeren.kazumiplayer.network.ServerPacketHandlers;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.screen.ScreenRemovalListeners;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

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
        // 屏幕方块被移除时清理同步组
        ScreenRemovalListeners.set(SyncGroupManager.get()::leaveByScreenId);

        // 初始化命令系统
        KazumiCommand.init(ruleManager, searchManager);
        NeoForge.EVENT_BUS.register(KazumiCommand.class);

        // 注入 C→S 网络包处理器
        PacketDispatcher.setServerHandler(new ServerPacketHandlers());

        KazumiLog.general.info("KazumiPlayer server side initialized ({} rules loaded)", ruleManager.count());
    }
}
