package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class ClientConfig {
    public static final ClientConfig CONFIG;
    public static final ModConfigSpec SPEC;

    // 视频默认音量 (0.0 - 1.0)
    public final ModConfigSpec.DoubleValue videoVolume;
    // 瞄准屏幕时自动加入同步播放
    public final ModConfigSpec.BooleanValue autoJoinSync;

    private ClientConfig(ModConfigSpec.Builder builder) {
        builder.push("general");

        videoVolume = builder
                .comment("默认视频音量 (0.0 = 静音, 1.0 = 最大)")
                .defineInRange("videoVolume", 1.0, 0.0, 1.0);

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
