package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleIndex;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class RuleCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PAGE_SIZE = 8;

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            RuleManager ruleManager, SearchManager searchManager) {

        return Commands.literal("rule")
            // --- pull ---
            .then(Commands.literal("pull")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        CommandSourceStack src = ctx.getSource();

                        src.sendSystemMessage(Component.literal("正在下载规则: " + name + " ..."));
                        ruleManager.getDownloader().fetchRule(name)
                            .thenAccept(rule -> {
                                ruleManager.install(rule);
                                src.sendSystemMessage(Component.literal("已安装规则: " + name));
                            })
                            .exceptionally(e -> {
                                src.sendSystemMessage(Component.literal("下载规则失败: " + e.getMessage()));
                                return null;
                            });

                        return 1;
                    })))

            // --- list [page] ---
            .then(Commands.literal("list")
                .executes(ctx -> {
                    sendRuleList(ctx.getSource(), ruleManager, 1);
                    return 1;
                })
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        sendRuleList(ctx.getSource(), ruleManager, page);
                        return 1;
                    })))

            // --- delete ---
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        ruleManager.listAll().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        if (ruleManager.delete(name)) {
                            ctx.getSource().sendSystemMessage(Component.literal("已删除规则: " + name));
                        } else {
                            ctx.getSource().sendFailure(Component.literal("规则不存在: " + name));
                        }
                        return 1;
                    })))

            // --- test (连通性) ---
            .then(Commands.literal("test")
                // 不指定规则 = 测试全部
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    var names = ruleManager.listAll();
                    if (names.isEmpty()) {
                        src.sendFailure(Component.literal("没有已安装的规则"));
                        return 0;
                    }
                    src.sendSystemMessage(Component.literal("正在测试全部 " + names.size() + " 个规则..."));
                    for (String name : names) {
                        Rule rule = ruleManager.get(name);
                        if (rule == null) continue;
                        long start = System.currentTimeMillis();
                        String n = name;
                        ruleManager.getEngine().search(rule, "test")
                            .thenAccept(r -> {
                                long lat = System.currentTimeMillis() - start;
                                src.sendSystemMessage(Component.literal(n + " 延迟: " + lat + "ms"));
                            })
                            .exceptionally(e -> {
                                src.sendSystemMessage(Component.literal(n + " 失败"));
                                return null;
                            });
                    }
                    return 1;
                })
                // 指定规则
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        ruleManager.listAll().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        Rule rule = ruleManager.get(name);
                        CommandSourceStack src = ctx.getSource();
                        if (rule == null) {
                            src.sendFailure(Component.literal("规则不存在: " + name));
                            return 0;
                        }
                        src.sendSystemMessage(Component.literal("正在测试 " + name + " ..."));
                        long start = System.currentTimeMillis();
                        ruleManager.getEngine().search(rule, "test")
                            .thenAccept(result -> {
                                long latency = System.currentTimeMillis() - start;
                                src.sendSystemMessage(Component.literal(
                                    name + " 连通正常，延迟: " + latency + "ms"));
                            })
                            .exceptionally(e -> {
                                src.sendSystemMessage(Component.literal(
                                    name + " 连通失败"));
                                return null;
                            });
                        return 1;
                    })))

            // --- pull-all (仅服务端) ---
            .then(Commands.literal("pull-all")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSystemMessage(Component.literal("正在获取规则目录..."));

                    ruleManager.getDownloader().fetchIndex()
                        .thenCompose(index -> {
                            src.sendSystemMessage(Component.literal(
                                "开始下载 " + index.size() + " 个规则..."));
                            var futures = index.stream()
                                .map(ri -> ruleManager.getDownloader().fetchRule(ri.getName())
                                    .thenAccept(ruleManager::install)
                                    .exceptionally(e -> null))
                                .toList();
                            return java.util.concurrent.CompletableFuture.allOf(
                                futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                                .thenApply(v -> ruleManager.count());
                        })
                        .thenAccept(count -> {
                            src.sendSystemMessage(Component.literal(
                                "下载完成，共安装了 " + count + " 个规则"));
                        })
                        .exceptionally(e -> {
                            src.sendSystemMessage(Component.literal(
                                "下载失败: " + e.getMessage()));
                            return null;
                        });

                    return 1;
                }));
    }

    /**
     * 发送分页的规则列表: 本地 + 远程，已安装标记并排最前，每页8个
     */
    private static void sendRuleList(CommandSourceStack src, RuleManager ruleManager, int page) {
        src.sendSystemMessage(Component.literal("正在获取规则列表..."));

        ruleManager.getDownloader().fetchIndex()
            .thenAccept(index -> {
                // 构建合并列表: 本地已安装排最前
                List<String> installed = ruleManager.listAll();
                List<DisplayEntry> entries = new ArrayList<>();

                // 已安装的排最前
                for (String name : installed) {
                    entries.add(new DisplayEntry(name, true, findVersion(index, name)));
                }
                // 远程未安装的
                for (RuleIndex ri : index) {
                    if (!ruleManager.getRules().containsKey(ri.getName())) {
                        entries.add(new DisplayEntry(ri.getName(), false, ri.getVersion()));
                    }
                }

                if (entries.isEmpty()) {
                    src.sendSystemMessage(Component.literal("规则列表为空"));
                    return;
                }

                int totalPages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
                final int currentPage = page > totalPages ? totalPages : page;

                src.sendSystemMessage(Component.literal(
                    "=== 规则列表 (" + currentPage + "/" + totalPages + " 页, 共 " + entries.size() + " 个) ==="));

                int start = (currentPage - 1) * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, entries.size());

                for (int i = start; i < end; i++) {
                    DisplayEntry e = entries.get(i);
                    if (e.installed) {
                        src.sendSystemMessage(ChatComponentUtil.suggestable(
                            "  [已安装] " + e.name + " v" + e.version,
                            "/kazumi rule test " + e.name));
                    } else {
                        src.sendSystemMessage(ChatComponentUtil.suggestable(
                            "  " + e.name + " v" + e.version,
                            "/kazumi rule pull " + e.name));
                    }
                }

                if (currentPage < totalPages) {
                    int nextPage = currentPage + 1;
                    src.sendSystemMessage(ChatComponentUtil.clickable(
                        ">>> 下一页 (第 " + nextPage + " 页)",
                        "/kazumi rule list " + nextPage,
                        "切换到第 " + nextPage + " 页"));
                }
            })
            .exceptionally(e -> {
                // 如果无法获取远程列表，回退到仅显示本地
                var names = ruleManager.listAll();
                if (names.isEmpty()) {
                    src.sendSystemMessage(Component.literal("无法获取规则列表且没有本地规则"));
                } else {
                    src.sendSystemMessage(Component.literal("无法获取远程列表，仅显示本地已安装:"));
                    for (String name : names) {
                        src.sendSystemMessage(ChatComponentUtil.suggestable(
                            "  [已安装] " + name,
                            "/kazumi rule test " + name));
                    }
                }
                return null;
            });
    }

    private static String findVersion(List<RuleIndex> index, String name) {
        for (RuleIndex ri : index) {
            if (ri.getName().equals(name)) return ri.getVersion();
        }
        return "?";
    }

    private record DisplayEntry(String name, boolean installed, String version) {}
}
