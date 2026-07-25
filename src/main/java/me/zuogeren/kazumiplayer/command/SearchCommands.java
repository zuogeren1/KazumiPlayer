package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.BangumiApi;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Map;

public class SearchCommands {
    private static final BangumiApi bangumiApi = new BangumiApi();

    // /kazumi search <keyword> - 搜索 bgm.tv
    public static LiteralArgumentBuilder<CommandSourceStack> buildSearch(
            RuleManager ruleManager, SearchManager searchManager) {
        return Commands.literal("search")
            .then(Commands.argument("keyword", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String keyword = StringArgumentType.getString(ctx, "keyword");
                    CommandSourceStack src = ctx.getSource();
                    src.sendSystemMessage(Component.literal("正在搜索: " + keyword + " ..."));

                    bangumiApi.search(keyword)
                        .thenAccept(subjects -> {
                            if (subjects.isEmpty()) {
                                src.sendSystemMessage(Component.literal("未找到 '" + keyword + "' 的结果"));
                                return;
                            }
                            int show = Math.min(subjects.size(), 10);
                            src.sendSystemMessage(Component.literal("=== 搜索: " + keyword + " (共 " + subjects.size() + " 个) ==="));
                            for (int i = 0; i < show; i++) {
                                var s = subjects.get(i);
                                String name = s.getDisplayName();
                                String date = s.getDate() != null ? " (" + s.getDate() + ")" : "";
                                src.sendSystemMessage(Component.literal((i + 1) + ". " + name + date));

                                if (!ruleManager.getRules().isEmpty()) {
                                    src.sendSystemMessage(ChatComponentUtil.clickable(
                                        "   [查源]",
                                        "/kazumi search-rule all " + name,
                                        "在所有规则中搜索: " + name));
                                }
                            }
                        })
                        .exceptionally(e -> {
                            src.sendSystemMessage(Component.literal("搜索失败: " + e.getMessage()));
                            return null;
                        });
                    return 1;
                }));
    }

    // /kazumi search-rule <规则名/all> <番剧名> - 在规则中搜索查源
    public static LiteralArgumentBuilder<CommandSourceStack> buildSearchRule(
            RuleManager ruleManager, SearchManager searchManager) {

        var searchAll = Commands.literal("all")
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    CommandSourceStack src = ctx.getSource();
                    src.sendSystemMessage(Component.literal("正在所有规则中搜索: " + name + " ..."));
                    searchManager.searchAll(ruleManager.getRules(), name)
                        .thenAccept(data -> showResults(src, data))
                        .exceptionally(e -> {
                            src.sendSystemMessage(Component.literal("搜索出错"));
                            return null;
                        });
                    return 1;
                }));

        var searchOne = Commands.argument("rule", StringArgumentType.string())
            .suggests((ctx, builder) -> {
                ruleManager.listAll().forEach(builder::suggest);
                return builder.buildFuture();
            })
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String ruleName = StringArgumentType.getString(ctx, "rule");
                    String name = StringArgumentType.getString(ctx, "name");
                    CommandSourceStack src = ctx.getSource();
                    Rule rule = ruleManager.get(ruleName);
                    if (rule == null) {
                        src.sendFailure(Component.literal("规则不存在: " + ruleName));
                        return 0;
                    }
                    src.sendSystemMessage(Component.literal("正在 " + ruleName + " 中搜索: " + name + " ..."));
                    searchManager.searchAll(Map.of(ruleName, rule), name)
                        .thenAccept(data -> showResults(src, data))
                        .exceptionally(e -> {
                            src.sendSystemMessage(Component.literal("搜索出错"));
                            return null;
                        });
                    return 1;
                }));

        return Commands.literal("search-rule")
            .then(searchAll)
            .then(searchOne);
    }

    private static void showResults(CommandSourceStack src,
                                     SearchManager.SearchResultData data) {
        if (data.results().isEmpty()) {
            src.sendSystemMessage(Component.literal("未找到结果"));
            return;
        }
        int total = data.results().values().stream().mapToInt(java.util.List::size).sum();
        src.sendSystemMessage(Component.literal("找到 " + total + " 个结果:"));
        for (var entry : data.results().entrySet()) {
            src.sendSystemMessage(ChatComponentUtil.header("[来源: " + entry.getKey() + "]"));
            for (var item : entry.getValue()) {
                src.sendSystemMessage(ChatComponentUtil.clickable(
                    "  " + item.item().name(),
                    "/kazumi play " + entry.getKey() + " " + item.id() + " 1",
                    "点击播放"));
            }
        }
    }
}
