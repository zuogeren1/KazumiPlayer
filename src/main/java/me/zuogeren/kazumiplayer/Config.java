package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public class Config {
    public static final Config CONFIG;
    public static final ModConfigSpec SPEC;

    // GitHub KazumiRules 仓库地址
    public final ModConfigSpec.ConfigValue<String> githubRulesRepoUrl;
    // 是否启用规则目录热重载（监视 rules 目录，外部修改 plugins.json 自动生效）
    public final ModConfigSpec.BooleanValue ruleHotReload;
    // 房间弹幕互发总开关（聊天栏监听）
    public final ModConfigSpec.BooleanValue danmakuEnabled;
    // 每屏房间弹幕旁路缓冲池容量
    public final ModConfigSpec.IntValue danmakuPoolCapacity;
    // 最大同时搜索的规则数
    public final ModConfigSpec.IntValue maxConcurrentSearches;
    // 单个规则搜索超时 (ms)
    public final ModConfigSpec.IntValue searchTimeoutMs;
    // 每规则最大搜索结果数
    public final ModConfigSpec.IntValue maxSearchResultsPerRule;
    // 搜索响应最大字节数
    public final ModConfigSpec.IntValue maxSearchResponseBytes;
    // B 站登录 Cookie（服务端代理解析用；COMMON 类型不参与网络同步，凭据不会外发给客户端）
    public final ModConfigSpec.ConfigValue<String> bilibiliCookie;
    // SSRF 域名白名单 (为空时仅允许公网地址)
    public final ModConfigSpec.ConfigValue<List<? extends String>> ssrfWhitelist;
    // 是否拦截 CGNAT 100.64/10 段（VPC/Docker 内网常见；代理 TUN 环境极少用该段映射）
    public final ModConfigSpec.BooleanValue ssrfBlockCgnat;
    // 是否拦截 IPv6 ULA fc00::/7 段（Clash/Mihomo 等 TUN 代理常用该段映射被代理域名，默认放行以兼容）
    public final ModConfigSpec.BooleanValue ssrfBlockUlaIpv6;

    private Config(ModConfigSpec.Builder builder) {
        builder.push("general");

        githubRulesRepoUrl = builder
                .comment("KazumiRules 仓库 raw URL")
                .define("githubRulesRepoUrl",
                        "https://raw.githubusercontent.com/Predidit/KazumiRules/main/");

        ruleHotReload = builder
                .comment("规则目录热重载：监视 rules 目录，外部修改 plugins.json 后自动重载并同步在线玩家",
                         "关闭后仍可手动执行 /kazumi rule reload")
                .define("ruleHotReload", true);

        builder.pop();
        builder.push("danmaku");

        danmakuEnabled = builder
                .comment("房间弹幕互发总开关：观看者在原版聊天栏发言时同步为同屏弹幕",
                         "关闭后聊天监听零开销")
                .define("danmakuEnabled", true);

        danmakuPoolCapacity = builder
                .comment("每屏房间弹幕旁路缓冲池容量（仅内存审计用，超限丢最旧）")
                .defineInRange("danmakuPoolCapacity", 500, 10, 10000);

        builder.pop();
        builder.push("limits");

        maxConcurrentSearches = builder
                .comment("同时搜索的最大规则数")
                .defineInRange("maxConcurrentSearches", 5, 1, 50);

        searchTimeoutMs = builder
                .comment("单个规则搜索超时时间 (毫秒)")
                .defineInRange("searchTimeoutMs", 10000, 5000, 120000);

        maxSearchResultsPerRule = builder
                .comment("每规则最大搜索结果数")
                .defineInRange("maxSearchResultsPerRule", 50, 1, 200);

        maxSearchResponseBytes = builder
                .comment("搜索/章节 HTML 响应最大字节数")
                .defineInRange("maxSearchResponseBytes", 5_242_880, 1024, 52_428_800);

        builder.pop();
        builder.push("bilibili");

        bilibiliCookie = builder
                .comment("B 站登录 Cookie（浏览器开发者工具复制完整 Cookie，至少含 SESSDATA）",
                         "留空 = 未登录：视频清晰度上限 720P、直播取默认码率",
                         "填入后可请求 1080P/原画（能否真正播放取决于接口返回的流形态）",
                         "凭据只在本端配置文件里：解析在服务端执行，只有有时效的直链/直播流地址下发给客户端",
                         "放在 COMMON 而非 SERVER 类型：SERVER 配置会被 NeoForge 明文同步给每个连入的客户端")
                .define("bilibiliCookie", "");

        builder.pop();
        builder.push("security");

        ssrfWhitelist = builder
                .comment("SSRF 域名白名单 (为空时仅允许公网地址)")
                .defineListAllowEmpty("ssrfWhitelist", List::of,
                        o -> o instanceof String s && !s.isBlank());

        ssrfBlockCgnat = builder
                .comment("SSRF 拦截 CGNAT 100.64/10 段（VPC/Docker 内网常见）",
                        "代理 TUN 环境若用该段映射外部域名需关闭")
                .define("ssrfBlockCgnat", true);

        ssrfBlockUlaIpv6 = builder
                .comment("SSRF 拦截 IPv6 ULA fc00::/7 段",
                        "Clash/Mihomo 等 TUN 代理常用该段映射被代理域名，默认放行以兼容；专用服务器建议开启")
                .define("ssrfBlockUlaIpv6", false);

        builder.pop();
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        Config config = new Config(builder);
        LogConfig.define(builder);
        Pair<Config, ModConfigSpec> pair = Pair.of(config, builder.build());
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
