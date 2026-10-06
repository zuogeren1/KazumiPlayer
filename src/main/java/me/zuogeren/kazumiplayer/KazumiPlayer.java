package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.network.NetworkManager;
import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.speaker.SpeakerRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

@Mod(KazumiPlayer.MODID)
public class KazumiPlayer {
    public static final String MODID = "kazumiplayer";


    /**
     * 共享入口（common）：只做两端通用的初始化。
     * 客户端/服务端专属逻辑分别在 kazumiplayer_client / kazumiplayer_server 附加 mod 中初始化。
     */
    public KazumiPlayer(IEventBus modEventBus, ModContainer modContainer) {
        KazumiLog.general.info("KazumiPlayer initializing...");

        // 全模组只用 COMMON 一种配置类型：SERVER 类型会被 NeoForge 把文件明文同步给每个连入的客户端
        // （ConfigSync.syncAllConfigs），凭据类内容不能放那里
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 配置加载/重载后应用日志分类级别（修改后即时生效）
        modEventBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == Config.SPEC) {
                LogConfig.applyAll();
                warnLegacyServerCookie();
            }
        });
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == Config.SPEC) {
                LogConfig.applyAll();
                warnLegacyServerCookie();
            }
        });

        // 网络数据包
        modEventBus.register(NetworkManager.class);

        // 方块、物品、方块实体
        VideoScreenRegistration.register(modEventBus);
        SpeakerRegistration.register(modEventBus);

        // 创造模式物品栏标签页
        CreativeTabRegistration.register(modEventBus);

        KazumiLog.general.info("KazumiPlayer common initialized");
    }

    /** 旧版存放 B 站凭据的 SERVER 类型配置文件（会被 NeoForge 明文同步给每个客户端，已弃用） */
    private static final String LEGACY_SERVER_CONFIG = "kazumiplayer-server.toml";

    /** 旧配置里 bilibiliCookie 的非空赋值（只做文本匹配，不解析 TOML） */
    private static final java.util.regex.Pattern LEGACY_COOKIE =
        java.util.regex.Pattern.compile("(?m)^\\s*bilibiliCookie\\s*=\\s*\"([^\"]+)\"");

    /**
     * B 站凭据已从 SERVER 类型迁到 COMMON 类型：旧文件里仍有非空凭据而新键为空时提示一次，
     * 避免升级后静默退回未登录状态。
     */
    private static void warnLegacyServerCookie() {
        if (!Config.CONFIG.bilibiliCookie.get().isBlank()) return;
        var configDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        var legacy = configDir.resolve(LEGACY_SERVER_CONFIG);
        if (!java.nio.file.Files.isRegularFile(legacy)) return;
        try {
            var matcher = LEGACY_COOKIE.matcher(java.nio.file.Files.readString(legacy));
            if (matcher.find() && !matcher.group(1).isBlank()) {
                KazumiLog.general.warn(
                    "Bilibili cookie found in legacy {} - move it to {} (server-type configs are synced to every client in plaintext)",
                    legacy, configDir.resolve("kazumiplayer-common.toml"));
            }
        } catch (Exception e) {
            KazumiLog.general.debug("Legacy server config probe failed: {}", String.valueOf(e.getMessage()));
        }
    }
}
