package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.client.ClientDisconnectHandler;
import me.zuogeren.kazumiplayer.client.ClientPlaybackScheduler;
import me.zuogeren.kazumiplayer.client.ClientClockSync;
import me.zuogeren.kazumiplayer.client.ClientModEvents;
import me.zuogeren.kazumiplayer.client.KazumiConfigScreen;
import me.zuogeren.kazumiplayer.client.BrowserCookieStore;
import me.zuogeren.kazumiplayer.client.gui.KazumiPlayerScreen;
import me.zuogeren.kazumiplayer.network.ClientPacketHandlers;
import me.zuogeren.kazumiplayer.rule.RuleRequestEnhancer;
import me.zuogeren.kazumiplayer.network.PacketDispatcher;
import me.zuogeren.kazumiplayer.screen.ScreenGuiOpeners;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
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

        // Cloth Config 可选：装了才提供配置界面（mods.toml 中声明为 optional 依赖）
        if (ModList.get().isLoaded("cloth_config")) {
            IConfigScreenFactory configScreenFactory = (container, parent) -> KazumiConfigScreen.create(parent);
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, configScreenFactory);
            KazumiLog.general.info("Cloth Config detected, config screen enabled");
        }

        // 客户端渲染器注册
        modEventBus.register(ClientModEvents.class);

        // 客户端杂项事件处理（鼠标防御 / krule 命令）
        NeoForge.EVENT_BUS.register(ClientDisconnectHandler.class);
        // 播放调度核心（每秒 tick：对账 + 视频屏幕/音响分派 + 生命周期清理）
        NeoForge.EVENT_BUS.register(ClientPlaybackScheduler.class);

        // 时钟同步探测（播放位置插值依赖）
        NeoForge.EVENT_BUS.register(ClientClockSync.class);

        // 全屏观影模式（HUD 画面渲染 + 退出按钮 + 光标保持）
        NeoForge.EVENT_BUS.register(me.zuogeren.kazumiplayer.client.ClientFullscreenState.class);

        // 注入 S→C 网络包处理器
        PacketDispatcher.setClientHandler(new ClientPacketHandlers());

        // 注入屏幕右键 GUI 打开器（common 方块经此钩子打开播放器主界面）
        ScreenGuiOpeners.set(screenPos -> Minecraft.getInstance().setScreen(new KazumiPlayerScreen(screenPos)));

        // 注入规则管理器打开钩子（规则管理器物品经此打开管理界面）
        me.zuogeren.kazumiplayer.rule.RuleManagerOpener.set(name ->
            Minecraft.getInstance().setScreen(
                new me.zuogeren.kazumiplayer.client.gui.RuleManagerScreen()));

        // 注入规则请求头增强器（浏览器收割的 Cookie 桥接到规则 HTTP 请求）
        RuleRequestEnhancer.set(BrowserCookieStore::headersFor);

        KazumiLog.general.info("KazumiPlayer client side initialized");
    }
}
