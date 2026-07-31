package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.network.NetworkManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.speaker.SpeakerRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(KazumiPlayer.MODID)
public class KazumiPlayer {
    public static final String MODID = "kazumiplayer";


    /**
     * 共享入口（common）：只做两端通用的初始化。
     * 客户端/服务端专属逻辑分别在 kazumiplayer_client / kazumiplayer_server 附加 mod 中初始化。
     */
    public KazumiPlayer(IEventBus modEventBus, ModContainer modContainer) {
        KazumiLog.general.info("KazumiPlayer initializing...");

        // 服务端通用配置
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 网络数据包
        modEventBus.register(NetworkManager.class);

        // 方块、物品、方块实体
        VideoScreenRegistration.register(modEventBus);
        SpeakerRegistration.register(modEventBus);

        KazumiLog.general.info("KazumiPlayer common initialized");
    }
}
