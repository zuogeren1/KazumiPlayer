package me.zuogeren.kazumiplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import me.zuogeren.kazumiplayer.rule.RuleManager;
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
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("kazumi")
                .then(RuleCommands.build(ruleManager, searchManager))
                .then(SearchCommands.buildSearch(ruleManager, searchManager))
                .then(SearchCommands.buildPage(ruleManager, searchManager))
                .then(SearchCommands.buildSearchRule(ruleManager, searchManager))
                .then(ScreenCommands.build())
                .then(PlayCommands.build(ruleManager, searchManager))
        );

        PlayCommands.registerTopLevel(dispatcher, ruleManager, searchManager);
    }
}
