package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class RuleCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            RuleManager ruleManager, SearchManager searchManager) {

        return Commands.literal("rule")
            // --- pull ---
            .then(Commands.literal("pull")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        // 补全: 从 KazumiRules index 获取可用规则名
                        // Phase 3: 提供基础补全
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        CommandSourceStack src = ctx.getSource();

                        ruleManager.getDownloader().fetchRule(name)
                            .thenAccept(rule -> {
                                ruleManager.install(rule);
                                src.sendSuccess(
                                    () -> Component.literal("已安装规则: " + name),
                                    true);
                            })
                            .exceptionally(e -> {
                                src.sendFailure(Component.literal("下载规则失败: " + e.getMessage()));
                                return null;
                            });

                        return 1;
                    })))

            // --- list ---
            .then(Commands.literal("list")
                .executes(ctx -> {
                    var names = ruleManager.listAll();
                    CommandSourceStack src = ctx.getSource();

                    if (names.isEmpty()) {
                        src.sendSuccess(() -> Component.literal("暂无已安装的规则"), false);
                    } else {
                        src.sendSuccess(() -> Component.literal("已安装规则 (" + names.size() + "):"), false);
                        for (String name : names) {
                            src.sendSuccess(() ->
                                ChatComponentUtil.suggestable(
                                    "  " + name,
                                    "/kazumi rule test " + name),
                                false);
                        }
                    }
                    return 1;
                }))

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
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("已删除规则: " + name), true);
                        } else {
                            ctx.getSource().sendFailure(
                                Component.literal("规则不存在: " + name));
                        }
                        return 1;
                    })))

            // --- test (客户端连通性测试) ---
            .then(Commands.literal("test")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        ruleManager.listAll().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        Rule rule = ruleManager.get(name);
                        if (rule == null) {
                            ctx.getSource().sendFailure(
                                Component.literal("规则不存在: " + name));
                            return 0;
                        }

                        long start = System.currentTimeMillis();
                        ruleManager.getEngine().search(rule, "test")
                            .thenAccept(result -> {
                                long latency = System.currentTimeMillis() - start;
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal("规则 " + name + " 连通正常，延迟: " + latency + "ms"),
                                    false);
                            })
                            .exceptionally(e -> {
                                ctx.getSource().sendFailure(
                                    Component.literal("规则 " + name + " 连通失败: " + e.getMessage()));
                                return null;
                            });

                        return 1;
                    })))

            // --- pull-all (仅服务端) ---
            .then(Commands.literal("pull-all")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();

                    ruleManager.getDownloader().fetchIndex()
                        .thenCompose(index -> {
                            src.sendSuccess(
                                () -> Component.literal("开始下载 " + index.size() + " 个规则..."),
                                true);

                            var futures = index.stream()
                                .map(ri -> ruleManager.getDownloader().fetchRule(ri.getName())
                                    .thenAccept(ruleManager::install)
                                    .exceptionally(e -> {
                                        LOGGER.warn("Failed to download {}", ri.getName(), e);
                                        return null;
                                    }))
                                .toList();

                            return java.util.concurrent.CompletableFuture.allOf(
                                futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                                .thenApply(v -> ruleManager.count());
                        })
                        .thenAccept(count -> {
                            src.sendSuccess(
                                () -> Component.literal("下载完成，共安装了 " + count + " 个规则"),
                                true);
                        })
                        .exceptionally(e -> {
                            src.sendFailure(Component.literal("下载失败: " + e.getMessage()));
                            return null;
                        });

                    return 1;
                }));
    }
}
