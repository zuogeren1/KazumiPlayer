package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public class Config {
    public static final Config CONFIG;
    public static final ModConfigSpec SPEC;

    // GitHub KazumiRules 仓库地址
    public final ModConfigSpec.ConfigValue<String> githubRulesRepoUrl;
    // 最大同时搜索的规则数
    public final ModConfigSpec.IntValue maxConcurrentSearches;
    // 单个规则搜索超时 (ms)
    public final ModConfigSpec.IntValue searchTimeoutMs;
    // 每规则最大搜索结果数
    public final ModConfigSpec.IntValue maxSearchResultsPerRule;
    // 搜索响应最大字节数
    public final ModConfigSpec.IntValue maxSearchResponseBytes;
    // SSRF 域名白名单 (为空时仅允许公网地址)
    public final ModConfigSpec.ConfigValue<List<? extends String>> ssrfWhitelist;

    private Config(ModConfigSpec.Builder builder) {
        builder.push("general");

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
                .defineInRange("searchTimeoutMs", 10000, 5000, 120000);

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
