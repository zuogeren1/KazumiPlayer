package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchCommands {
    private static final BangumiApi bangumiApi = new BangumiApi();
    private static final int PAGE_SIZE = 8;

    // /kazumi search <keyword> [page] - 搜索 bgm.tv, 8条/页
    public static LiteralArgumentBuilder<CommandSourceStack> buildSearch(
            RuleManager ruleManager, SearchManager searchManager) {
        return Commands.literal("search")
            .then(Commands.argument("keyword", StringArgumentType.greedyString())
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> doBangumiSearch(ctx.getSource(), ruleManager,
                        StringArgumentType.getString(ctx, "keyword"),
                        IntegerArgumentType.getInteger(ctx, "page"))))
                .executes(ctx -> doBangumiSearch(ctx.getSource(), ruleManager,
                    StringArgumentType.getString(ctx, "keyword"), 1)));
    }

    private static int doBangumiSearch(CommandSourceStack src, RuleManager ruleManager,
                                        String keyword, int page) {
        int offset = (page - 1) * PAGE_SIZE;
        src.sendSystemMessage(Component.literal("正在搜索: " + keyword + " (第 " + page + " 页) ..."));
        bangumiApi.search(keyword, PAGE_SIZE, offset).thenAccept(subjects -> {
            if (subjects.isEmpty()) {
                src.sendSystemMessage(Component.literal("未找到 '" + keyword + "' 的结果"));
                return;
            }
            src.sendSystemMessage(Component.literal(
                "=== 搜索: " + keyword + " (第 " + page + " 页, " + subjects.size() + " 个) ==="));
            for (int i = 0; i < subjects.size(); i++) {
                var s = subjects.get(i);
                String name = s.getDisplayName();
                String date = s.getDate() != null ? " (" + s.getDate() + ")" : "";
                src.sendSystemMessage(Component.literal((offset + i + 1) + ". " + name + date));
                if (!ruleManager.getRules().isEmpty()) {
                    src.sendSystemMessage(ChatComponentUtil.clickable(
                        "   [查源]", "/kazumi search-rule all " + name,
                        "在所有规则中搜索: " + name));
                }
            }
            // 下一页按钮
            if (subjects.size() >= PAGE_SIZE) {
                int next = page + 1;
                src.sendSystemMessage(ChatComponentUtil.clickable(
                    ">>> 下一页 (第 " + next + " 页)",
                    "/kazumi search " + keyword + " " + next,
                    "切换第 " + next + " 页"));
            }
        }).exceptionally(e -> {
            src.sendSystemMessage(Component.literal("搜索失败: " + e.getMessage()));
            return null;
        });
        return 1;
    }

    // /kazumi search-rule all <name> [page] - 在所有规则中搜索
    // /kazumi search-rule <rule> <name> [page] - 在指定规则中搜索
    public static LiteralArgumentBuilder<CommandSourceStack> buildSearchRule(
            RuleManager ruleManager, SearchManager searchManager) {

        // search-rule all <name> [page]
        var all = Commands.literal("all")
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> doRuleSearch(ctx.getSource(), ruleManager, searchManager,
                        null, StringArgumentType.getString(ctx, "name"),
                        IntegerArgumentType.getInteger(ctx, "page"))))
                .executes(ctx -> doRuleSearch(ctx.getSource(), ruleManager, searchManager,
                    null, StringArgumentType.getString(ctx, "name"), 1)));

        // search-rule <rule> <name> [page]
        var one = Commands.argument("rule", StringArgumentType.string())
            .suggests((ctx, builder) -> {
                ruleManager.listAll().forEach(builder::suggest);
                return builder.buildFuture();
            })
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> doRuleSearch(ctx.getSource(), ruleManager, searchManager,
                        StringArgumentType.getString(ctx, "rule"),
                        StringArgumentType.getString(ctx, "name"),
                        IntegerArgumentType.getInteger(ctx, "page"))))
                .executes(ctx -> doRuleSearch(ctx.getSource(), ruleManager, searchManager,
                    StringArgumentType.getString(ctx, "rule"),
                    StringArgumentType.getString(ctx, "name"), 1)));

        return Commands.literal("search-rule").then(all).then(one);
    }

    private static int doRuleSearch(CommandSourceStack src, RuleManager ruleManager,
                                     SearchManager searchManager, String ruleName,
                                     String keyword, int page) {
        Map<String, Rule> rules;
        if (ruleName == null) {
            rules = ruleManager.getRules();
            src.sendSystemMessage(Component.literal("正在所有规则中搜索: " + keyword + " ..."));
        } else {
            Rule rule = ruleManager.get(ruleName);
            if (rule == null) {
                src.sendFailure(Component.literal("规则不存在: " + ruleName));
                return 0;
            }
            rules = Map.of(ruleName, rule);
            src.sendSystemMessage(Component.literal("正在 " + ruleName + " 中搜索: " + keyword + " ..."));
        }

        int p = Math.max(1, page);
        searchManager.searchAll(rules, keyword)
            .thenAccept(data -> showPage(src, data, p))
            .exceptionally(e -> { src.sendSystemMessage(Component.literal("搜索出错")); return null; });
        return 1;
    }

    private static void showPage(CommandSourceStack src,
                                  SearchManager.SearchResultData data, int page) {
        List<ResultEntry> all = new ArrayList<>();
        for (var entry : data.results().entrySet()) {
            for (var item : entry.getValue()) {
                all.add(new ResultEntry(entry.getKey(), item));
            }
        }
        if (all.isEmpty()) {
            src.sendSystemMessage(Component.literal("未找到结果"));
            return;
        }
        int totalPages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        final int cp = page > totalPages ? totalPages : page;

        src.sendSystemMessage(ChatComponentUtil.header(
            "=== 搜索结果 第 " + cp + "/" + totalPages + " 页 (共 " + all.size() + " 个) ==="));

        int start = (cp - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, all.size());
        for (int i = start; i < end; i++) {
            var e = all.get(i);
            src.sendSystemMessage(ChatComponentUtil.clickable(
                (i + 1) + ". [" + e.ruleName + "] " + e.entry.item().name(),
                "/kazumi play " + e.ruleName + " " + e.entry.id() + " 1",
                "点击播放"));
        }
        if (cp < totalPages) {
            int next = cp + 1;
            src.sendSystemMessage(ChatComponentUtil.clickable(
                ">>> 下一页 (第 " + next + " 页)",
                "/kazumi search-rule all 翻页 " + next,
                "切换到第 " + next + " 页"));
        }
    }

    private record ResultEntry(String ruleName, SearchManager.SearchResultEntry entry) {}
}
