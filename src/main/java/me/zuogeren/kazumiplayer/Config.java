package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public class Config {
    public static final Config CONFIG;
    public static final ModConfigSpec SPEC;

    // MCEF 浏览器生命周期策略
    public final ModConfigSpec.EnumValue<McefLifecycle> mcefLifecycle;
    // GitHub KazumiRules 仓库地址
    public final ModConfigSpec.ConfigValue<String> githubRulesRepoUrl;
    // 最大同时搜索的规则数
    public final ModConfigSpec.IntValue maxConcurrentSearches;
    // 单个规则搜索超时 (ms)
    public final ModConfigSpec.IntValue searchTimeoutMs;
    // 最大同时嗅探数
    public final ModConfigSpec.IntValue maxConcurrentSniffs;
    // 每个客户端最大同时播放数
    public final ModConfigSpec.IntValue maxConcurrentPlays;
    // 嗅探超时 (秒)
    public final ModConfigSpec.IntValue sniffTimeoutSeconds;
    // 每规则最大搜索结果数
    public final ModConfigSpec.IntValue maxSearchResultsPerRule;
    // 搜索响应最大字节数
    public final ModConfigSpec.IntValue maxSearchResponseBytes;
    // SSRF 域名白名单 (为空时仅允许公网地址)
    public final ModConfigSpec.ConfigValue<List<? extends String>> ssrfWhitelist;

    public enum McefLifecycle {
        ON_DEMAND,
        PERSISTENT
    }

    private Config(ModConfigSpec.Builder builder) {
        builder.push("general");

        mcefLifecycle = builder
                .comment("MCEF 浏览器生命周期策略",
                        "ON_DEMAND - 每次播放创建浏览器，嗅探完成后释放",
                        "PERSISTENT - 保持单个浏览器实例复用")
                .defineEnum("mcefLifecycle", McefLifecycle.ON_DEMAND);

        githubRulesRepoUrl = builder
                .comment("KazumiRules 仓库 raw URL")
                .define("githubRulesRepoUrl",
                        "https://raw.githubusercontent.com/Predidit/KazumiRules/main/");

        builder.pop();
        builder.push("limits");

        maxConcurrentSearches = builder
                .comment("同时搜索的最大规则数")
                .defineInRange("maxConcurrentSearches", 5, 1, 50);

        searchTimeoutMs = builder
                .comment("单个规则搜索超时时间 (毫秒)")
                .defineInRange("searchTimeoutMs", 30000, 5000, 120000);

        maxConcurrentSniffs = builder
                .comment("客户端最大同时嗅探数")
                .defineInRange("maxConcurrentSniffs", 3, 1, 10);

        maxConcurrentPlays = builder
                .comment("每个客户端最大同时播放屏幕数")
                .defineInRange("maxConcurrentPlays", 3, 1, 10);

        sniffTimeoutSeconds = builder
                .comment("视频嗅探超时时间 (秒)")
                .defineInRange("sniffTimeoutSeconds", 30, 5, 120);

        maxSearchResultsPerRule = builder
                .comment("每规则最大搜索结果数")
                .defineInRange("maxSearchResultsPerRule", 50, 1, 200);

        maxSearchResponseBytes = builder
                .comment("搜索/章节 HTML 响应最大字节数")
                .defineInRange("maxSearchResponseBytes", 5_242_880, 1024, 52_428_800);

        builder.pop();
        builder.push("security");

        ssrfWhitelist = builder
                .comment("SSRF 域名白名单 (为空时仅允许公网地址)")
                .defineListAllowEmpty("ssrfWhitelist", List::of,
                        o -> o instanceof String s && !s.isBlank());

        builder.pop();
    }

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
