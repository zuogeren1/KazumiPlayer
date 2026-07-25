package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Map;

public class SearchCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            RuleManager ruleManager, SearchManager searchManager) {

        // /kazumi search <keyword> - 搜索所有规则
        var searchAll = Commands.argument("keyword", StringArgumentType.greedyString())
            .executes(ctx -> {
                String keyword = StringArgumentType.getString(ctx, "keyword");
                CommandSourceStack src = ctx.getSource();
                executeSearch(src, ruleManager, searchManager, keyword, ruleManager.getRules());
                return 1;
            });

        // /kazumi search in <rule> <keyword> - 在指定规则中搜索
        var searchIn = Commands.literal("in")
            .then(Commands.argument("rule", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    ruleManager.listAll().forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String ruleName = StringArgumentType.getString(ctx, "rule");
                        String keyword = StringArgumentType.getString(ctx, "keyword");
                        CommandSourceStack src = ctx.getSource();

                        Rule rule = ruleManager.get(ruleName);
                        if (rule == null) {
                            src.sendFailure(Component.literal("规则不存在: " + ruleName));
                            return 0;
                        }
                        executeSearch(src, ruleManager, searchManager, keyword,
                                Map.of(ruleName, rule));
                        return 1;
                    })));

        return Commands.literal("search")
            .then(searchIn)
            .then(searchAll);
    }

    private static void executeSearch(CommandSourceStack src, RuleManager ruleManager,
                                       SearchManager searchManager, String keyword,
                                       Map<String, Rule> rules) {
        if (rules.isEmpty()) {
            src.sendFailure(Component.literal("没有可用的规则"));
            return;
        }

        src.sendSystemMessage(Component.literal("正在搜索: " + keyword + " ..."));

        searchManager.searchAll(rules, keyword)
            .thenAccept(data -> {
                if (data.results().isEmpty()) {
                    src.sendSystemMessage(Component.literal("未找到 '" + keyword + "' 的结果"));
                    return;
                }
                int total = data.results().values().stream()
                        .mapToInt(java.util.List::size).sum();
                int ruleCount = data.results().size();
                src.sendSystemMessage(Component.literal("找到 " + total + " 个结果 (来自 " + ruleCount + " 个源):"));

                for (var entry : data.results().entrySet()) {
                    String ruleName = entry.getKey();
                    src.sendSystemMessage(ChatComponentUtil.header("[来源: " + ruleName + "]"));
                    for (var item : entry.getValue()) {
                        src.sendSystemMessage(ChatComponentUtil.clickable(
                            "  " + item.item().name(),
                            "/kazumi play " + ruleName + " " + item.id() + " 1",
                            "点击播放: " + item.item().name()));
                    }
                }
            })
            .exceptionally(e -> {
                src.sendSystemMessage(Component.literal("搜索出错: " + e.getMessage()));
                return null;
            });
    }
}
