package me.zuogeren.kazumiplayer.screen;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class VideoScreenRegistration {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(KazumiPlayer.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(KazumiPlayer.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, KazumiPlayer.MODID);

    // registerBlock: frame internally calls setId on Properties before factory
    // Supplier pattern (1.21.11+) defers Properties construction
    public static final DeferredBlock<VideoScreenBlock> VIDEO_SCREEN_BLOCK =
            BLOCKS.registerBlock("video_screen",
                    VideoScreenBlock::new,
                    () -> BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .instabreak());

    public static final DeferredItem<BlockItem> VIDEO_SCREEN_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem(VIDEO_SCREEN_BLOCK);

    public static final DeferredItem<me.zuogeren.kazumiplayer.item.ScreenRemoteItem> SCREEN_REMOTE =
            ITEMS.registerItem("screen_remote", me.zuogeren.kazumiplayer.item.ScreenRemoteItem::new, props -> props);

    public static final DeferredItem<me.zuogeren.kazumiplayer.item.RemoteViewerItem> REMOTE_VIEWER =
            ITEMS.registerItem("remote_viewer", me.zuogeren.kazumiplayer.item.RemoteViewerItem::new, props -> props);

    // BlockEntityType: (BlockEntitySupplier, onlyOpCanSetNbt, Block...)
    // Docs: onlyOpCanSetNbt = false for normal block entities
    public static final Supplier<BlockEntityType<VideoScreenBlockEntity>> VIDEO_SCREEN_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("video_screen",
                    () -> new BlockEntityType<>(
                            VideoScreenBlockEntity::new,
                            false,
                            VIDEO_SCREEN_BLOCK.get()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
