package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.BangumiApi;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.search.SearchSessionCache;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchCommands {
    private static final BangumiApi bangumiApi = new BangumiApi();
    private static final SearchSessionCache sessionCache = new SearchSessionCache();
    private static final int PAGE_SIZE = 8;

    // /kazumi search <keyword> - 搜索 bgm.tv (创建会话，缓存全量结果)
    public static LiteralArgumentBuilder<CommandSourceStack> buildSearch(
            RuleManager ruleManager, SearchManager searchManager) {
        return Commands.literal("search")
            .then(Commands.argument("keyword", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String keyword = StringArgumentType.getString(ctx, "keyword");
                    CommandSourceStack src = ctx.getSource();
                    String sessionId = sessionCache.createSession(keyword);

                    src.sendSystemMessage(Component.literal("正在搜索: " + keyword + " ..."));
                    // 首次拉取 20 条，后续翻页从缓存读取
                    bangumiApi.search(keyword, 20, 0).thenAccept(subjects -> {
                        sessionCache.addResults(sessionId, subjects);
                        var page = sessionCache.getPage(sessionId, 1, PAGE_SIZE);
                        if (page == null) return;
                        showBangumiPage(src, page, ruleManager);
                    }).exceptionally(e -> {
                        src.sendSystemMessage(Component.literal("搜索失败: " + e.getMessage()));
                        return null;
                    });
                    return 1;
                }));
    }

    // /kazumi page <sessionId> <page> - 翻页（从缓存读取）
    public static LiteralArgumentBuilder<CommandSourceStack> buildPage(
            RuleManager ruleManager, SearchManager searchManager) {
        return Commands.literal("page")
            .then(Commands.argument("sessionId", StringArgumentType.string())
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String sessionId = StringArgumentType.getString(ctx, "sessionId");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        CommandSourceStack src = ctx.getSource();
                        var result = sessionCache.getPage(sessionId, page, PAGE_SIZE);
                        if (result == null) {
                            src.sendFailure(Component.literal("会话已过期，请重新搜索"));
                            return 0;
                        }
                        showBangumiPage(src, result, ruleManager);
                        return 1;
                    })));
    }

    private static void showBangumiPage(CommandSourceStack src,
                                         SearchSessionCache.PageResult page, RuleManager ruleManager) {
        if (!page.hasResults() || page.items().isEmpty()) {
            src.sendSystemMessage(Component.literal("未找到 '" + page.keyword() + "' 的结果"));
            return;
        }
        src.sendSystemMessage(Component.literal(
            "=== 搜索: " + page.keyword() + " (第 " + page.page() + "/" + page.totalPages() + " 页, 共 " + page.total() + " 个) ==="));
        int base = (page.page() - 1) * PAGE_SIZE;
        for (int i = 0; i < page.items().size(); i++) {
            var s = page.items().get(i);
            String name = s.getDisplayName();
            String date = s.getDate() != null ? " (" + s.getDate() + ")" : "";
            src.sendSystemMessage(Component.literal((base + i + 1) + ". " + name + date));
            if (!ruleManager.getRules().isEmpty()) {
                src.sendSystemMessage(ChatComponentUtil.clickable(
                    "   [查源]", "/kazumi search-rule all " + name,
                    "在所有规则中搜索: " + name));
            }
        }
        var nav = Component.literal("").withStyle(net.minecraft.ChatFormatting.GRAY);
        if (page.page() > 1) {
            int prev = page.page() - 1;
            nav.append(ChatComponentUtil.clickable(
                "<<< 上一页  ",
                "/kazumi page " + page.sessionId() + " " + prev,
                "切换到第 " + prev + " 页"));
        }
        if (page.hasNext()) {
            int next = page.page() + 1;
            nav.append(ChatComponentUtil.clickable(
                ">>> 下一页",
                "/kazumi page " + page.sessionId() + " " + next,
                "切换到第 " + next + " 页"));
        }
        if (page.page() > 1 || page.hasNext()) {
            src.sendSystemMessage(nav);
        }
    }

    // /kazumi search-rule all <name> [page] - 在所有规则中搜索
    // /kazumi search-rule <rule> <name> [page] - 在指定规则中搜索
    public static LiteralArgumentBuilder<CommandSourceStack> buildSearchRule(
            RuleManager ruleManager, SearchManager searchManager) {
        var all = Commands.literal("all")
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> doRuleSearch(ctx.getSource(), ruleManager, searchManager,
                        null, StringArgumentType.getString(ctx, "name"),
                        IntegerArgumentType.getInteger(ctx, "page"))))
                .executes(ctx -> doRuleSearch(ctx.getSource(), ruleManager, searchManager,
                    null, StringArgumentType.getString(ctx, "name"), 1)));

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
            .thenAccept(data -> showRulePage(src, data, p))
            .exceptionally(e -> { src.sendSystemMessage(Component.literal("搜索出错")); return null; });
        return 1;
    }

    private static void showRulePage(CommandSourceStack src,
                                      SearchManager.SearchResultData data, int page) {
        List<ResultEntry> all = new ArrayList<>();
        for (var entry : data.results().entrySet()) {
            for (var item : entry.getValue()) {
                all.add(new ResultEntry(entry.getKey(), item));
            }
        }
        if (all.isEmpty()) { src.sendSystemMessage(Component.literal("未找到结果")); return; }
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
                "/kazumi episodes " + e.ruleName + " " + e.entry.id(), "点击查看集数"));
        }
        var nav = Component.literal("").withStyle(net.minecraft.ChatFormatting.GRAY);
        if (cp > 1) {
            int prev = cp - 1;
            nav.append(ChatComponentUtil.clickable("<<< 上一页  ",
                "/kazumi search-rule all 翻页 " + prev, "切换到第 " + prev + " 页"));
        }
        if (cp < totalPages) {
            int next = cp + 1;
            nav.append(ChatComponentUtil.clickable(">>> 下一页",
                "/kazumi search-rule all 翻页 " + next, "切换到第 " + next + " 页"));
        }
        if (cp > 1 || cp < totalPages) {
            src.sendSystemMessage(nav);
        }
    }

    private record ResultEntry(String ruleName, SearchManager.SearchResultEntry entry) {}
}
