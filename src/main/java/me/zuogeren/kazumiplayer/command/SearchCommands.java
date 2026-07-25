package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.search.SearchManager;
import me.zuogeren.kazumiplayer.util.ChatComponentUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class SearchCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            RuleManager ruleManager, SearchManager searchManager) {

        return Commands.literal("search")
            .then(Commands.argument("keyword", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String keyword = StringArgumentType.getString(ctx, "keyword");
                    CommandSourceStack src = ctx.getSource();

                    if (ruleManager.count() == 0) {
                        src.sendFailure(Component.literal("没有已安装的规则，请先用 /kazumi rule pull <名称> 下载规则"));
                        return 0;
                    }

                    src.sendSystemMessage(Component.literal("正在搜索: " + keyword + " ..."));

                    // 非阻塞异步搜索
                    searchManager.searchAll(ruleManager.getRules(), keyword)
                        .thenAccept(data -> {
                            if (data.results().isEmpty()) {
                                src.sendSystemMessage(Component.literal("未找到结果"));
                                return;
                            }
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

                    return 1;
                }));
    }
}
