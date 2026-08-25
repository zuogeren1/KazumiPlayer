package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.rule.RuleDownloader;
import me.zuogeren.kazumiplayer.rule.RuleIndex;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import me.zuogeren.kazumiplayer.network.RuleSyncBroadcast;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class RuleCommands {
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

                        KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.downloading", name);
                        ruleManager.getDownloader().fetchRule(name)
                            .thenAccept(rule -> {
                                ruleManager.install(rule);
                                KazumiMessages.sendSuccessKey(src, "kazumiplayer.cmd.rule.installed", name);
                                if (rule.isDeprecated()) {
                                    src.sendSystemMessage(KazumiMessages.warnOf(
                                        Component.translatable("kazumiplayer.cmd.rule_deprecated_warn", name)));
                                }
                                RuleSyncBroadcast.broadcast(ruleManager);
                            })
                            .exceptionally(e -> {
                                KazumiMessages.sendErrorKey(src, "kazumiplayer.cmd.rule.download_failed", RuleDownloader.friendlyError(name, e));
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
                            KazumiMessages.sendSuccessKey(ctx.getSource(), "kazumiplayer.cmd.rule.deleted", name);
                            RuleSyncBroadcast.broadcast(ruleManager);
                        } else {
                            ctx.getSource().sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule_not_found", name)));
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
                        src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule.none_installed")));
                        return 0;
                    }
                    KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.testing_all", String.valueOf(names.size()));
                    for (String name : names) {
                        Rule rule = ruleManager.get(name);
                        if (rule == null) continue;
                        long start = System.currentTimeMillis();
                        String n = name;
                        ruleManager.getEngine().search(rule, "test")
                            .thenAccept(r -> {
                                long lat = System.currentTimeMillis() - start;
                                KazumiMessages.sendSuccessKey(src, "kazumiplayer.cmd.rule.test_ok", n, String.valueOf(lat));
                            })
                            .exceptionally(e -> {
                                KazumiMessages.sendErrorKey(src, "kazumiplayer.cmd.rule.test_fail_short", n);
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
                            src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule_not_found", name)));
                            return 0;
                        }
                        if (rule.isDeprecated()) {
                            src.sendFailure(KazumiMessages.warnOf(
                                Component.translatable("kazumiplayer.cmd.rule_deprecated_short", name)));
                        }
                        KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.testing", name);
                        long start = System.currentTimeMillis();
                        ruleManager.getEngine().search(rule, "test")
                            .thenAccept(result -> {
                                long latency = System.currentTimeMillis() - start;
                                KazumiMessages.sendSuccessKey(src, "kazumiplayer.gui.rule.test_ok", name, String.valueOf(latency));
                            })
                            .exceptionally(e -> {
                                KazumiMessages.sendErrorKey(src, "kazumiplayer.gui.rule.test_fail", name);
                                return null;
                            });
                        return 1;
                    })))

            // --- pull-all (仅服务端) ---
            .then(Commands.literal("pull-all")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.fetching_index");

                    ruleManager.getDownloader().fetchIndex(true)
                        .thenCompose(index -> {
                            KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.downloading_all", String.valueOf(index.size()));
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
                            KazumiMessages.sendSuccessKey(src, "kazumiplayer.cmd.rule.downloaded_all", String.valueOf(count));
                            RuleSyncBroadcast.broadcast(ruleManager);
                        })
                        .exceptionally(e -> {
                            KazumiMessages.sendErrorKey(src, "kazumiplayer.cmd.rule.download_failed", RuleDownloader.friendlyError("index", e));
                            return null;
                        });

                    return 1;
                }))

            // --- update [name] ---
            // 更新已安装规则：不填规则名则更新全部已安装规则
            .then(Commands.literal("update")
                .executes(ctx -> updateAll(ctx.getSource(), ruleManager))
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        ruleManager.listAll().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> updateOne(ctx.getSource(), ruleManager,
                        StringArgumentType.getString(ctx, "name")))))

            // --- reload ---
            // 从磁盘热重载 plugins.json（外部手改规则文件后无需重启；解析失败保留内存现状）
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    int count = ruleManager.reloadLive();
                    if (count >= 0) {
                        KazumiMessages.sendSuccessKey(src, "kazumiplayer.cmd.rule.reloaded",
                            String.valueOf(count));
                        RuleSyncBroadcast.broadcast(ruleManager);
                    } else {
                        KazumiMessages.sendErrorKey(src, "kazumiplayer.cmd.rule.reload_failed",
                            String.valueOf(ruleManager.count()));
                    }
                    return count >= 0 ? 1 : 0;
                }));
    }

    /** 更新单个已安装规则 */
    private static int updateOne(CommandSourceStack src, RuleManager ruleManager, String name) {
        Rule existing = ruleManager.get(name);
        if (existing == null) {
            src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule.not_installed", name, name)));
            return 0;
        }
        KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.updating", name);
        ruleManager.getDownloader().fetchRule(name)
            .thenAccept(rule -> {
                ruleManager.install(rule);
                KazumiMessages.sendSuccessKey(src, "kazumiplayer.cmd.rule.updated", name, rule.getVersion());
                if (rule.isDeprecated()) {
                    src.sendSystemMessage(KazumiMessages.warnOf(
                        Component.translatable("kazumiplayer.cmd.rule_deprecated_short", name)));
                }
                RuleSyncBroadcast.broadcast(ruleManager);
            })
            .exceptionally(e -> {
                KazumiMessages.sendErrorKey(src, "kazumiplayer.cmd.rule.update_failed", RuleDownloader.friendlyError(name, e));
                return null;
            });
        return 1;
    }

    /** 更新全部已安装规则 */
    private static int updateAll(CommandSourceStack src, RuleManager ruleManager) {
        List<String> installed = ruleManager.listAll();
        if (installed.isEmpty()) {
            src.sendFailure(KazumiMessages.errorOf(Component.translatable("kazumiplayer.cmd.rule.none_installed_pullall")));
            return 0;
        }
        KazumiMessages.sendInfoKey(src, "kazumiplayer.cmd.rule.updating_all", String.valueOf(installed.size()));
        var futures = installed.stream()
            .map(name -> ruleManager.getDownloader().fetchRule(name)
                .thenAccept(ruleManager::install)
                .exceptionally(e -> {
                    KazumiLog.rule.warn("Failed to update rule {}: {}", name, e.getMessage());
                    return null;
                }))
            .toList();
        java.util.concurrent.CompletableFuture.allOf(
                futures.toArray(new java.util.concurrent.CompletableFuture[0]))
            .thenAccept(v -> {
                KazumiMessages.sendSuccessKey(src, "kazumiplayer.cmd.rule.updated_all", String.valueOf(ruleManager.count()));
                // 提示是否有弃用规则
                List<String> deprecated = ruleManager.listAll().stream()
                    .filter(n -> {
                        var r = ruleManager.get(n);
                        return r != null && r.isDeprecated();
                    })
                    .toList();
                if (!deprecated.isEmpty()) {
                    KazumiMessages.sendWarnKey(src, "kazumiplayer.cmd.rule.deprecated_list", String.join(", ", deprecated));
                }
                RuleSyncBroadcast.broadcast(ruleManager);
            });
        return 1;
    }

    /**
     * 发送分页的规则列表: 本地 + 远程，已安装标记并排最前，每页8个
     */
    private static void sendRuleList(CommandSourceStack src, RuleManager ruleManager, int page) {
        KazumiMessages.sendInfoKey(src, "kazumiplayer.gui.rule.status_fetching");

        ruleManager.getDownloader().fetchIndex()
            .thenAccept(index -> {
                // 构建合并列表: 本地已安装排最前
                List<String> installed = ruleManager.listAll();
                List<DisplayEntry> entries = new ArrayList<>();

                // 已安装的排最前
                for (String name : installed) {
                    Rule local = ruleManager.get(name);
                    entries.add(new DisplayEntry(name, true, findVersion(index, name),
                            local != null && local.isDeprecated()));
                }
                // 远程未安装的
                for (RuleIndex ri : index) {
                    if (!ruleManager.getRules().containsKey(ri.getName())) {
                        entries.add(new DisplayEntry(ri.getName(), false, ri.getVersion(), false));
                    }
                }

                if (entries.isEmpty()) {
                    KazumiMessages.sendWarnKey(src, "kazumiplayer.cmd.rule.list_empty_short");
                    return;
                }

                int totalPages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
                final int currentPage = page > totalPages ? totalPages : page;

                src.sendSystemMessage(KazumiMessages.separator());
                src.sendSystemMessage(Component.translatable("kazumiplayer.cmd.rule.list_header", currentPage, totalPages, entries.size())
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                int start = (currentPage - 1) * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, entries.size());

                for (int i = start; i < end; i++) {
                    DisplayEntry e = entries.get(i);
                    var depMark = e.deprecated ? Component.translatable("kazumiplayer.cmd.rule.dep_mark") : Component.empty();
                    if (e.installed) {
                        src.sendSystemMessage(ChatComponentUtil.suggestable(
                            Component.translatable("kazumiplayer.cmd.rule.entry_installed", e.name, e.version).append(depMark),
                            "/kazumi rule test " + e.name));
                    } else {
                        var line = Component.literal("  " + e.name + " v" + e.version);
                        if (e.deprecated) line.append(depMark);
                        src.sendSystemMessage(ChatComponentUtil.suggestable(
                            line,
                            "/kazumi rule pull " + e.name));
                    }
                }

                if (currentPage < totalPages) {
                    int nextPage = currentPage + 1;
                    src.sendSystemMessage(ChatComponentUtil.clickable(
                        Component.translatable("kazumiplayer.cmd.nav.next_page", nextPage),
                        "/kazumi rule list " + nextPage,
                        Component.translatable("kazumiplayer.cmd.nav.goto_page", nextPage)));
                }
            })
            .exceptionally(e -> {
                // 如果无法获取远程列表，回退到仅显示本地
                var names = ruleManager.listAll();
                if (names.isEmpty()) {
                    KazumiMessages.sendWarnKey(src, "kazumiplayer.cmd.rule.unavailable_no_local");
                } else {
                    KazumiMessages.sendWarnKey(src, "kazumiplayer.cmd.rule.remote_unavailable_local_only");
                    for (String name : names) {
                        src.sendSystemMessage(ChatComponentUtil.suggestable(
                            Component.translatable("kazumiplayer.cmd.rule.entry_installed_noname", name),
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

    private record DisplayEntry(String name, boolean installed, String version, boolean deprecated) {}
}
