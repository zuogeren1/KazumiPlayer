package me.zuogeren.kazumiplayer;

import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;
import me.zuogeren.kazumiplayer.client.ClientModEvents;
import me.zuogeren.kazumiplayer.command.KazumiCommand;
import me.zuogeren.kazumiplayer.network.NetworkManager;
import me.zuogeren.kazumiplayer.rule.RuleEngine;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.item.ConnectionToolItem;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.speaker.SpeakerRegistration;
import me.zuogeren.kazumiplayer.search.SearchManager;
import net.neoforged.neoforge.registries.DeferredRegister;
import me.zuogeren.kazumiplayer.sync.SyncGroupManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(KazumiPlayer.MODID)
public class KazumiPlayer {
    public static final String MODID = "kazumiplayer";
    private static final Logger LOGGER = LogUtils.getLogger();

    public KazumiPlayer(IEventBus modEventBus, ModContainer modContainer, Dist dist) {
        LOGGER.info("KazumiPlayer initializing...");

        // 配置
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        if (dist.isClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        }

        // 规则引擎、规则管理器、搜索管理器
        RuleEngine ruleEngine = new RuleEngine();
        RuleManager ruleManager = new RuleManager(FMLPaths.CONFIGDIR.get());
        ruleManager.loadAll();
        SearchManager searchManager = new SearchManager(ruleEngine);

        // 同步组管理
        SyncGroupManager.init();
        NeoForge.EVENT_BUS.register(SyncGroupManager.get());

        // 初始化命令系统
        KazumiCommand.init(ruleManager, searchManager);
        NeoForge.EVENT_BUS.register(KazumiCommand.class);

        // 网络数据包
        modEventBus.register(NetworkManager.class);

        // 方块、物品、方块实体
        VideoScreenRegistration.register(modEventBus);
        SpeakerRegistration.register(modEventBus);

        // 连接工具
        var itemReg = DeferredRegister.createItems(MODID);
        itemReg.register("connection_tool", ConnectionToolItem::new);
        itemReg.register(modEventBus);

        // 客户端渲染器
        if (dist.isClient()) {
            modEventBus.register(ClientModEvents.class);
            NeoForge.EVENT_BUS.register(ClientDisconnectHandler.class);
        }

        LOGGER.info("KazumiPlayer initialized on {} ({} rules loaded)", dist, ruleManager.count());
    }
}
