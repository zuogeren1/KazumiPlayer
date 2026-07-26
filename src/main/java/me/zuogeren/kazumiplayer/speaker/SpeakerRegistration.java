package me.zuogeren.kazumiplayer.speaker;

import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class SpeakerRegistration {
    static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(KazumiPlayer.MODID);
    static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(KazumiPlayer.MODID);

    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, KazumiPlayer.MODID);

    public static final DeferredBlock<SpeakerBlock> SPEAKER_BLOCK =
        BLOCKS.registerBlock("speaker",
            SpeakerBlock::new,
            () -> BlockBehaviour.Properties.of()
                .sound(SoundType.WOOD)
                .strength(1.0f)
                .noOcclusion());

    public static final DeferredItem<BlockItem> SPEAKER_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem(SPEAKER_BLOCK);

    public static final Supplier<BlockEntityType<SpeakerBlockEntity>> SPEAKER_BLOCK_ENTITY =
        BLOCK_ENTITY_TYPES.register("speaker",
            () -> new BlockEntityType<>(
                SpeakerBlockEntity::new, false, SPEAKER_BLOCK.get()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
