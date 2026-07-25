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

        builder.pop();
    }

    static {
        Pair<ClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
