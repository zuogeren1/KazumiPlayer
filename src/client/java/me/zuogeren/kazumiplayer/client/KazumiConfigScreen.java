package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.LogConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Cloth Config 配置界面（可选依赖：未安装 Cloth Config 时不会注册）。
 *
 * 直接读写现有 ModConfigSpec 值，不引入 Cloth 自己的序列化：
 * - 日志分类开关 -> Config (COMMON) 的 "log" 分组，保存后即时应用到 Log4j2
 * - 播放/功能配置 -> ClientConfig (CLIENT) 各分组
 */
public final class KazumiConfigScreen {
    private KazumiConfigScreen() {}

    private static final List<String> LOG_CATEGORIES = List.of(
            "general", "playback", "sniff", "render", "screen",
            "speaker", "network", "sync", "command", "search", "rule", "http");

    private static final java.util.Map<String, String> LOG_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("general", "生命周期/通用"),
            java.util.Map.entry("playback", "播放引擎"),
            java.util.Map.entry("sniff", "视频嗅探"),
            java.util.Map.entry("render", "渲染"),
            java.util.Map.entry("screen", "屏幕方块"),
            java.util.Map.entry("speaker", "音响"),
            java.util.Map.entry("network", "网络包"),
            java.util.Map.entry("sync", "同步组"),
            java.util.Map.entry("command", "命令"),
            java.util.Map.entry("search", "搜索"),
            java.util.Map.entry("rule", "规则引擎"),
            java.util.Map.entry("http", "HTTP 请求"));

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("KazumiPlayer 配置"));
        ConfigEntryBuilder entry = builder.entryBuilder();

        // ---- 日志分类 DEBUG 开关（写入 COMMON 配置，保存即应用） ----
        ConfigCategory logCategory = builder.getOrCreateCategory(Component.literal("日志"));
        for (String category : LOG_CATEGORIES) {
            logCategory.addEntry(entry.startBooleanToggle(
                            Component.literal("DEBUG · " + LOG_LABELS.getOrDefault(category, category)),
                            LogConfig.isDebugEnabled(category))
                    .setDefaultValue(false)
                    .setSaveConsumer(enabled -> LogConfig.setDebugEnabled(category, enabled))
                    .build());
        }

        // ---- MCEF 浏览器 ----
        ConfigCategory mcefCategory = builder.getOrCreateCategory(Component.literal("MCEF"));
        mcefCategory.addEntry(entry.startEnumSelector(
                        Component.literal("浏览器生命周期"),
                        ClientConfig.McefLifecycle.class,
                        ClientConfig.CONFIG.mcefLifecycle.get())
                .setDefaultValue(ClientConfig.McefLifecycle.ON_DEMAND)
                .setSaveConsumer(value -> ClientConfig.CONFIG.mcefLifecycle.set(value))
                .build());

        // ---- 播放 ----
        ConfigCategory playbackCategory = builder.getOrCreateCategory(Component.literal("播放"));
        playbackCategory.addEntry(entry.startDoubleField(
                        Component.literal("默认音量"),
                        ClientConfig.CONFIG.videoVolume.get())
                .setDefaultValue(1.0)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(value -> ClientConfig.CONFIG.videoVolume.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.literal("最大同时嗅探数"),
                        ClientConfig.CONFIG.maxConcurrentSniffs.get())
                .setDefaultValue(3).setMin(1).setMax(10)
                .setSaveConsumer(value -> ClientConfig.CONFIG.maxConcurrentSniffs.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.literal("最大同时播放屏幕数"),
                        ClientConfig.CONFIG.maxConcurrentPlays.get())
                .setDefaultValue(3).setMin(1).setMax(10)
                .setSaveConsumer(value -> ClientConfig.CONFIG.maxConcurrentPlays.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.literal("嗅探超时 (秒)"),
                        ClientConfig.CONFIG.sniffTimeoutSeconds.get())
                .setDefaultValue(30).setMin(5).setMax(120)
                .setSaveConsumer(value -> ClientConfig.CONFIG.sniffTimeoutSeconds.set(value))
                .build());
        playbackCategory.addEntry(entry.startBooleanToggle(
                        Component.literal("瞄准屏幕时自动加入同步播放"),
                        ClientConfig.CONFIG.autoJoinSync.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.autoJoinSync.set(value))
                .build());

        // 保存时：日志级别即时应用（其余配置项在使用处实时读取，同样即时生效）
        builder.setSavingRunnable(LogConfig::applyAll);

        return builder.build();
    }
}
