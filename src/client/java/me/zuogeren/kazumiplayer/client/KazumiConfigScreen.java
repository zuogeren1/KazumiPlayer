package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.Config;
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
            "speaker", "audio", "network", "sync", "command", "search", "rule", "http", "danmaku");

    private static final java.util.Map<String, String> LOG_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("general", "kazumiplayer.config.log.general"),
            java.util.Map.entry("playback", "kazumiplayer.config.log.playback"),
            java.util.Map.entry("sniff", "kazumiplayer.config.log.sniff"),
            java.util.Map.entry("render", "kazumiplayer.config.log.render"),
            java.util.Map.entry("screen", "kazumiplayer.config.log.screen"),
            java.util.Map.entry("speaker", "kazumiplayer.config.log.speaker"),
            java.util.Map.entry("audio", "kazumiplayer.config.log.audio"),
            java.util.Map.entry("network", "kazumiplayer.config.log.network"),
            java.util.Map.entry("sync", "kazumiplayer.config.log.sync"),
            java.util.Map.entry("command", "kazumiplayer.config.log.command"),
            java.util.Map.entry("search", "kazumiplayer.config.log.search"),
            java.util.Map.entry("rule", "kazumiplayer.config.log.rule"),
            java.util.Map.entry("http", "kazumiplayer.config.log.http"),
            java.util.Map.entry("danmaku", "kazumiplayer.config.log.danmaku"));

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("kazumiplayer.config.title"));
        ConfigEntryBuilder entry = builder.entryBuilder();

        // ---- 日志分类 DEBUG 开关（写入 COMMON 配置，保存即应用） ----
        ConfigCategory logCategory = builder.getOrCreateCategory(Component.translatable("kazumiplayer.config.category_log"));
        for (String category : LOG_CATEGORIES) {
            logCategory.addEntry(entry.startBooleanToggle(
                            Component.translatable(
                    LOG_LABELS.getOrDefault(category, "kazumiplayer.config.log." + category)),
                            LogConfig.isDebugEnabled(category))
                    .setDefaultValue(false)
                    .setSaveConsumer(enabled -> LogConfig.setDebugEnabled(category, enabled))
                    .build());
        }

        // ---- 播放 ----
        ConfigCategory playbackCategory = builder.getOrCreateCategory(Component.translatable("kazumiplayer.config.category_playback"));
        playbackCategory.addEntry(entry.startDoubleField(
                        Component.translatable("kazumiplayer.config.video_volume"),
                        ClientConfig.CONFIG.videoVolume.get())
                .setDefaultValue(1.0)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(value -> ClientConfig.CONFIG.videoVolume.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.translatable("kazumiplayer.config.max_sniffs"),
                        ClientConfig.CONFIG.maxConcurrentSniffs.get())
                .setDefaultValue(3).setMin(1).setMax(10)
                .setSaveConsumer(value -> ClientConfig.CONFIG.maxConcurrentSniffs.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.translatable("kazumiplayer.config.max_plays"),
                        ClientConfig.CONFIG.maxConcurrentPlays.get())
                .setDefaultValue(3).setMin(1).setMax(10)
                .setSaveConsumer(value -> ClientConfig.CONFIG.maxConcurrentPlays.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.translatable("kazumiplayer.config.sniff_timeout"),
                        ClientConfig.CONFIG.sniffTimeoutSeconds.get())
                .setDefaultValue(30).setMin(5).setMax(120)
                .setSaveConsumer(value -> ClientConfig.CONFIG.sniffTimeoutSeconds.set(value))
                .build());
        playbackCategory.addEntry(entry.startEnumSelector(
                        Component.translatable("kazumiplayer.config.video_fit"),
                        ClientConfig.VideoFit.class,
                        ClientConfig.CONFIG.videoFit.get())
                .setDefaultValue(ClientConfig.VideoFit.STRETCH)
                .setTooltip(Component.translatable("kazumiplayer.config.video_fit_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.videoFit.set(value))
                .build());
        playbackCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.idle_frame"),
                        ClientConfig.CONFIG.showIdleScreenFrame.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("kazumiplayer.config.idle_frame_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.showIdleScreenFrame.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.translatable("kazumiplayer.config.fullscreen_coverage_pct"),
                        ClientConfig.CONFIG.fullscreenCoverage.get())
                .setDefaultValue(100).setMin(1).setMax(100)
                .setTooltip(Component.translatable("kazumiplayer.config.fullscreen_coverage_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.fullscreenCoverage.set(value))
                .build());
        playbackCategory.addEntry(entry.startIntField(
                        Component.translatable("kazumiplayer.config.fullscreen_opacity_pct"),
                        ClientConfig.CONFIG.fullscreenOpacity.get())
                .setDefaultValue(100).setMin(10).setMax(100)
                .setTooltip(Component.translatable("kazumiplayer.config.fullscreen_opacity_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.fullscreenOpacity.set(value))
                .build());

        // 保存时：日志级别即时应用 + 显式写盘（ModConfigSpec.set 只改内存，不调 save 重启会重置）
        builder.setSavingRunnable(() -> {
            LogConfig.applyAll();
            Config.SPEC.save();
            ClientConfig.SPEC.save();
        });

        return builder.build();
    }
}
