package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 服务端配置：B 站凭据等服务端专属设置。
 * B 站解析由服务端代理执行（客户端只拿到有时效的可播放地址），凭据不下发到客户端；
 * 客户端仅在服务端解析失败/超时/未连接时，用各自的本地凭据回落解析。
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
                         "填入后可请求 1080P/原画（能否真正播放取决于接口返回的流形态）",
                         "凭据只留在服务端：解析在服务端执行，只有有时效的直链/直播流地址会下发给客户端")
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
