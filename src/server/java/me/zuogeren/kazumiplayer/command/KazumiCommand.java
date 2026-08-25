package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import me.zuogeren.kazumiplayer.Config;
import me.zuogeren.kazumiplayer.rule.RuleManager;
import me.zuogeren.kazumiplayer.rule.RuleWatcher;
import me.zuogeren.kazumiplayer.search.SearchManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /kazumi 根命令，注册所有子命令
 */
public class KazumiCommand {
    private static RuleManager ruleManager;
    private static SearchManager searchManager;

    public static void init(RuleManager rm, SearchManager sm) {
        ruleManager = rm;
        searchManager = sm;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 规则目录热重载：挂在此处因配置此时必已加载（mod 构造器阶段读取配置值会抛异常），
        // 且集成服每次开档都会触发本事件——ensureStarted 幂等，异常退出后也能补启
        if (Config.CONFIG.ruleHotReload.get()) {
            RuleWatcher.ensureStarted(ruleManager);
        }

        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("kazumi")
                .then(RuleCommands.build(ruleManager, searchManager))
                .then(SearchCommands.buildSearch(ruleManager, searchManager))
                .then(SearchCommands.buildPage(ruleManager, searchManager))
                .then(SearchCommands.buildSearchRule(ruleManager, searchManager))
                .then(ScreenCommands.build())
                .then(PlayCommands.build(ruleManager, searchManager))
                .then(PlayCommands.buildControl())
                .then(PlayCommands.buildEpisodes(ruleManager, searchManager))
                .then(QueueCommands.build())
        );

        // 旧一级写法的透明转发别名（play-url / join）
        PlayCommands.registerCompatAliases(dispatcher);
    }
}
