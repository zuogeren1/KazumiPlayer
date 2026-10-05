package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 服务端配置：需要随玩家登录下发到客户端的共享设置。
 * B 站接口请求由客户端发出（WaterMedia 与解析链路都在客户端），
 * 服务端填写的凭据因此必须在登录时同步过去才能生效。
 */
public class ServerConfig {
    public static final ServerConfig CONFIG;
    public static final ModConfigSpec SPEC;

    /** B 站登录 Cookie（浏览器复制完整 Cookie，至少含 SESSDATA）；空 = 未登录 */
    public final ModConfigSpec.ConfigValue<String> bilibiliCookie;

    private ServerConfig(ModConfigSpec.Builder builder) {
        builder.push("bilibili");

        bilibiliCookie = builder
                .comment("B 站登录 Cookie（浏览器开发者工具复制完整 Cookie，至少含 SESSDATA）",
                         "留空 = 未登录：视频清晰度上限 720P、直播取默认码率",
                         "填入后可请求 1080P/原画（是否可播取决于 WaterMedia 解析结果）",
                         "该值在玩家登录时下发到各客户端，仅用于 B 站平台接口请求")
                .define("bilibiliCookie", "");

        builder.pop();
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ServerConfig config = new ServerConfig(builder);
        Pair<ServerConfig, ModConfigSpec> pair = Pair.of(config, builder.build());
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
