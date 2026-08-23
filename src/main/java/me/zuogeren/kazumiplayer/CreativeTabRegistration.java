package me.zuogeren.kazumiplayer;

import me.zuogeren.kazumiplayer.screen.VideoScreenRegistration;
import me.zuogeren.kazumiplayer.speaker.SpeakerRegistration;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式物品栏标签页：聚合本模组全部物品与方块。
 */
public class CreativeTabRegistration {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, KazumiPlayer.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> KAZUMIPLAYER_TAB =
            CREATIVE_MODE_TABS.register("kazumiplayer", () -> CreativeModeTab.builder(
                    CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.kazumiplayer.kazumiplayer"))
                .icon(() -> new net.minecraft.world.item.ItemStack(
                    VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ITEM.get()))
                .displayItems((parameters, output) -> {
                    output.accept(VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ITEM.get());
                    output.accept(SpeakerRegistration.SPEAKER_BLOCK_ITEM.get());
                    output.accept(SpeakerRegistration.CONNECTION_TOOL.get());
                    output.accept(VideoScreenRegistration.SCREEN_REMOTE.get());
                    output.accept(VideoScreenRegistration.REMOTE_VIEWER.get());
                    output.accept(VideoScreenRegistration.RULE_MANAGER.get());
                })
                .build());

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
