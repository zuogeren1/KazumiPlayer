package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.LogConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.zuogeren.kazumiplayer.client.gui.BilibiliLoginScreen;
import me.zuogeren.kazumiplayer.client.gui.ConfigButtonEntry;
import net.minecraft.client.Minecraft;
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

    /** 本地登录态文案（只含字符数，不含明文） */
    private static Component localStatusText() {
        int len = BilibiliCredentials.get().length();
        return len > 0
                ? Component.translatable("kazumiplayer.config.bilibili_local_logged", len)
                : Component.translatable("kazumiplayer.config.bilibili_local_none");
    }

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
        playbackCategory.addEntry(entry.startEnumSelector(
                        Component.translatable("kazumiplayer.config.video_cache_mode"),
                        ClientConfig.VideoCacheMode.class,
                        ClientConfig.CONFIG.videoCacheMode.get())
                .setDefaultValue(ClientConfig.VideoCacheMode.STREAM)
                .setTooltip(Component.translatable("kazumiplayer.config.video_cache_mode_tooltip"))
                .setSaveConsumer(value -> {
                    ClientConfig.CONFIG.videoCacheMode.set(value);
                    me.zuogeren.kazumiplayer.KazumiPlayerClient.applyWaterMediaStreamMode();
                })
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

        // ---- 弹幕（B 站片内/直播 + 房间互发共池；R 站面板同口径） ----
        ConfigCategory danmakuCategory = builder.getOrCreateCategory(
                Component.translatable("kazumiplayer.config.category_danmaku"));
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_enabled"),
                        ClientConfig.CONFIG.danmakuEnabled.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuEnabled.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_bili_video"),
                        ClientConfig.CONFIG.danmakuBilibiliVideo.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuBilibiliVideo.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_bili_live"),
                        ClientConfig.CONFIG.danmakuBilibiliLive.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuBilibiliLive.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_room_chat"),
                        ClientConfig.CONFIG.danmakuRoomChat.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuRoomChat.set(value))
                .build());
        danmakuCategory.addEntry(entry.startEnumSelector(
                        Component.translatable("kazumiplayer.config.danmaku_density"),
                        ClientConfig.DanmakuDensity.class,
                        ClientConfig.CONFIG.danmakuDensity.get())
                .setDefaultValue(ClientConfig.DanmakuDensity.NORMAL)
                .setTooltip(Component.translatable("kazumiplayer.config.danmaku_density_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuDensity.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_allow_overlap"),
                        ClientConfig.CONFIG.danmakuAllowOverlap.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("kazumiplayer.config.danmaku_allow_overlap_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuAllowOverlap.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_show_scroll"),
                        ClientConfig.CONFIG.danmakuShowScroll.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuShowScroll.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_show_top"),
                        ClientConfig.CONFIG.danmakuShowTop.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuShowTop.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_show_bottom"),
                        ClientConfig.CONFIG.danmakuShowBottom.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuShowBottom.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_show_colored"),
                        ClientConfig.CONFIG.danmakuShowColored.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuShowColored.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_show_advanced"),
                        ClientConfig.CONFIG.danmakuShowAdvanced.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("kazumiplayer.config.danmaku_show_advanced_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuShowAdvanced.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_outline"),
                        ClientConfig.CONFIG.danmakuOutline.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuOutline.set(value))
                .build());
        danmakuCategory.addEntry(entry.startIntSlider(
                        Component.translatable("kazumiplayer.config.danmaku_opacity"),
                        (int) Math.round(ClientConfig.CONFIG.danmakuOpacity.get() * 100.0),
                        10, 100)
                .setDefaultValue(90)
                .setTextGetter(percent -> Component.translatable(
                        "kazumiplayer.config.danmaku_opacity_value", percent))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuOpacity.set(value / 100.0))
                .build());
        danmakuCategory.addEntry(entry.startIntSlider(
                        Component.translatable("kazumiplayer.config.danmaku_font_scale"),
                        (int) Math.round(ClientConfig.CONFIG.danmakuFontScale.get() * 100.0),
                        50, 300)
                .setDefaultValue(100)
                .setTextGetter(percent -> Component.translatable(
                        "kazumiplayer.config.danmaku_font_scale_value", percent))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuFontScale.set(value / 100.0))
                .build());
        danmakuCategory.addEntry(entry.startIntSlider(
                        Component.translatable("kazumiplayer.config.danmaku_speed"),
                        (int) Math.round(ClientConfig.CONFIG.danmakuSpeedMultiplier.get() * 100.0),
                        25, 400)
                .setDefaultValue(100)
                .setTextGetter(percent -> Component.translatable(
                        "kazumiplayer.config.danmaku_speed_value", percent))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuSpeedMultiplier.set(value / 100.0))
                .build());
        danmakuCategory.addEntry(entry.startIntSlider(
                        Component.translatable("kazumiplayer.config.danmaku_area_ratio"),
                        (int) Math.round(ClientConfig.CONFIG.danmakuAreaRatio.get() * 100.0),
                        10, 100)
                .setDefaultValue(50)
                .setTextGetter(percent -> Component.translatable(
                        "kazumiplayer.config.danmaku_area_ratio_value", percent))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuAreaRatio.set(value / 100.0))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_scale_with_screen"),
                        ClientConfig.CONFIG.danmakuScaleWithScreen.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("kazumiplayer.config.danmaku_scale_with_screen_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuScaleWithScreen.set(value))
                .build());
        danmakuCategory.addEntry(entry.startIntField(
                        Component.translatable("kazumiplayer.config.danmaku_time_offset"),
                        ClientConfig.CONFIG.danmakuTimeOffsetMs.get())
                .setDefaultValue(0).setMin(-60000).setMax(60000)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuTimeOffsetMs.set(value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_fullscreen"),
                        ClientConfig.CONFIG.danmakuShowInFullscreen.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuShowInFullscreen.set(value))
                .build());
        danmakuCategory.addEntry(entry.startStrField(
                        Component.translatable("kazumiplayer.config.danmaku_block_words"),
                        ClientConfig.CONFIG.danmakuBlockWords.get())
                .setDefaultValue("")
                .setTooltip(Component.translatable("kazumiplayer.config.danmaku_block_words_tooltip"))
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuBlockWords.set(value == null ? "" : value))
                .build());
        danmakuCategory.addEntry(entry.startBooleanToggle(
                        Component.translatable("kazumiplayer.config.danmaku_auto_sync_block_words"),
                        ClientConfig.CONFIG.danmakuAutoSyncBlockWords.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> ClientConfig.CONFIG.danmakuAutoSyncBlockWords.set(value))
                .build());
        danmakuCategory.addEntry(entry.startTextDescription(
                Component.translatable("kazumiplayer.config.danmaku_account_words_status",
                        accountBlockWordCount()))
            .build());

        // ---- B 站（扫码登录 / 手工粘贴凭据；本地优先于服务端下发的共享凭据） ----
        ConfigCategory biliCategory = builder.getOrCreateCategory(
                Component.translatable("kazumiplayer.config.category_bilibili"));
        // 状态行：只报长度，不回显凭据明文（配置界面常被截图/录屏）
        biliCategory.addEntry(entry.startTextDescription(
                Component.translatable("kazumiplayer.config.bilibili_status", localStatusText()))
            .build());
        // 输入框保持为空：留空=不修改，粘贴新值=覆盖本地凭据
        biliCategory.addEntry(entry.startStrField(
                        Component.translatable("kazumiplayer.config.bilibili_cookie"),
                        "")
                .setDefaultValue("")
                .setTooltip(Component.translatable("kazumiplayer.config.bilibili_cookie_tooltip"))
                .setSaveConsumer(value -> {
                    if (value == null || value.isBlank()) return; // 留空表示不修改（避免误清空）
                    ClientConfig.CONFIG.bilibiliCookie.set(value.trim());
                })
                .build());
        biliCategory.addEntry(new ConfigButtonEntry(
                Component.translatable("kazumiplayer.config.bilibili_login"),
                Component.translatable("kazumiplayer.config.bilibili_login_button"),
                () -> Minecraft.getInstance().setScreen(
                        new BilibiliLoginScreen(Minecraft.getInstance().screen))));
        biliCategory.addEntry(new ConfigButtonEntry(
                Component.translatable("kazumiplayer.config.bilibili_copy"),
                Component.translatable("kazumiplayer.config.bilibili_copy_button"),
                () -> {
                    String cookie = BilibiliCredentials.get();
                    if (cookie.isEmpty()) {
                        KazumiClientMessages.chatWarn(
                            Component.translatable("kazumiplayer.msg.bili_cookie_empty").getString());
                        return;
                    }
                    Minecraft.getInstance().keyboardHandler.setClipboard(cookie);
                    KazumiClientMessages.chatSuccess(Component.translatable(
                            "kazumiplayer.msg.bili_cookie_copied", cookie.length()).getString());
                }));
        biliCategory.addEntry(new ConfigButtonEntry(
                Component.translatable("kazumiplayer.config.bilibili_logout"),
                Component.translatable("kazumiplayer.config.bilibili_logout_button"),
                () -> {
                    ClientConfig.CONFIG.bilibiliCookie.set("");
                    ClientConfig.SPEC.save();
                    KazumiClientMessages.chatSuccess(
                        Component.translatable("kazumiplayer.msg.bili_cookie_cleared").getString());
                }));

        // 保存时：日志级别即时应用 + 显式写盘（ModConfigSpec.set 只改内存，不调 save 重启会重置）
        builder.setSavingRunnable(() -> {
            LogConfig.applyAll();
            Config.SPEC.save();
            ClientConfig.SPEC.save();
        });

        return builder.build();
    }

    /** 账号同步下来的屏蔽词条数（换行分隔，空行不计） */
    private static int accountBlockWordCount() {
        String raw = ClientConfig.CONFIG.danmakuAccountBlockWords.get();
        if (raw == null || raw.isBlank()) return 0;
        int count = 0;
        for (String line : raw.split("\\R")) {
            if (!line.isBlank()) count++;
        }
        return count;
    }
}
