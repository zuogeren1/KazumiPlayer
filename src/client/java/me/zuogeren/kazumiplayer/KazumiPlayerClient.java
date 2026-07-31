package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;
import me.zuogeren.kazumiplayer.client.ClientModEvents;
import me.zuogeren.kazumiplayer.network.ClientPacketHandlers;
import me.zuogeren.kazumiplayer.network.PacketDispatcher;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端附加 mod：注册客户端专属逻辑（配置、渲染器、客户端 tick、S→C 包处理）。
 * 仅存在于客户端 jar。
 */
@Mod(KazumiPlayerClient.MODID)
public class KazumiPlayerClient {
    public static final String MODID = "kazumiplayer_client";

    public KazumiPlayerClient(IEventBus modEventBus, ModContainer modContainer, Dist dist) {
        if (!dist.isClient()) {
            return; // 服务端环境无需客户端逻辑
        }

        KazumiLog.general.info("KazumiPlayer client side initializing...");

        // 客户端配置
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        // 客户端渲染器注册
        modEventBus.register(ClientModEvents.class);

        // 客户端核心调度（每秒 tick）
        NeoForge.EVENT_BUS.register(ClientDisconnectHandler.class);

        // 注入 S→C 网络包处理器
        PacketDispatcher.setClientHandler(new ClientPacketHandlers());

        KazumiLog.general.info("KazumiPlayer client side initialized");
    }
}
