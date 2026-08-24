package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class ClientConfig {
    public static final ClientConfig CONFIG;
    public static final ModConfigSpec SPEC;

    // MCEF 浏览器生命周期策略
    public final ModConfigSpec.EnumValue<McefLifecycle> mcefLifecycle;
    // 视频默认音量 (0.0 - 1.0)
    public final ModConfigSpec.DoubleValue videoVolume;
    // 最大同时嗅探数
    public final ModConfigSpec.IntValue maxConcurrentSniffs;
    // 每个客户端最大同时播放数
    public final ModConfigSpec.IntValue maxConcurrentPlays;
    // 嗅探超时 (秒)
    public final ModConfigSpec.IntValue sniffTimeoutSeconds;
    // 瞄准屏幕时自动加入同步播放
    public final ModConfigSpec.BooleanValue autoJoinSync;
    // 全屏观影画面覆盖窗口的百分比 (1-100)
    public final ModConfigSpec.IntValue fullscreenCoverage;
    // 全屏观影画面不透明度百分比 (10-100)
    public final ModConfigSpec.IntValue fullscreenOpacity;
    // 屏幕比例与视频不一致时的播放画面适配方式
    public final ModConfigSpec.EnumValue<VideoFit> videoFit;
    // 未播放时在世界中显示屏幕面位置预览框（便于调整屏幕）
    public final ModConfigSpec.BooleanValue showIdleScreenFrame;

    /** 屏幕为非常规比例时视频画面的适配方式 */
    public enum VideoFit {
        /** 拉伸：画面填满整个屏幕面（可能变形） */
        STRETCH,
        /** 等比缩放后居中：保持视频原始宽高比，屏幕面内居中显示（两侧或上下留边） */
        CONTAIN
    }

    public enum McefLifecycle {
        ON_DEMAND,
        PERSISTENT
    }

    private ClientConfig(ModConfigSpec.Builder builder) {
        builder.push("mcef");

        mcefLifecycle = builder
                .comment("MCEF 浏览器生命周期策略",
                        "ON_DEMAND - 每次播放创建浏览器，嗅探完成后释放",
                        "PERSISTENT - 保持单个浏览器实例复用")
                .defineEnum("mcefLifecycle", McefLifecycle.ON_DEMAND);

        builder.pop();
        builder.push("playback");

        videoVolume = builder
                .comment("默认视频音量 (0.0 = 静音, 1.0 = 最大)")
                .defineInRange("videoVolume", 1.0, 0.0, 1.0);

        maxConcurrentSniffs = builder
                .comment("最大同时嗅探数")
                .defineInRange("maxConcurrentSniffs", 3, 1, 10);

        maxConcurrentPlays = builder
                .comment("每个客户端最大同时播放屏幕数")
                .defineInRange("maxConcurrentPlays", 3, 1, 10);

        sniffTimeoutSeconds = builder
                .comment("视频嗅探超时时间 (秒)")
                .defineInRange("sniffTimeoutSeconds", 30, 5, 120);

        autoJoinSync = builder
                .comment("瞄准屏幕时自动加入同步播放")
                .define("autoJoinSync", true);

        fullscreenCoverage = builder
                .comment("全屏观影画面占窗口的百分比 (1-100)，居中显示，小于 100 时四周透出游戏世界")
                .defineInRange("fullscreenCoverage", 100, 1, 100);

        fullscreenOpacity = builder
                .comment("全屏观影画面不透明度百分比 (10-100)")
                .defineInRange("fullscreenOpacity", 100, 10, 100);

        videoFit = builder
                .comment("屏幕比例与视频不一致时的播放行为",
                        "STRETCH - 拉伸填满整个屏幕面（可能变形）",
                        "CONTAIN - 等比缩放后居中（保持视频宽高比，留边）")
                .defineEnum("videoFit", VideoFit.STRETCH);

        showIdleScreenFrame = builder
                .comment("未播放时在世界中显示屏幕面位置预览框（便于调整屏幕的位置与大小）")
                .define("showIdleScreenFrame", true);

        builder.pop();
    }

    static {
        Pair<ClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
