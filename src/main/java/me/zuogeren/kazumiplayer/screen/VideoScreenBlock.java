package me.zuogeren.kazumiplayer.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class VideoScreenBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = EnumProperty.create("facing", Direction.class,
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

    public VideoScreenBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        return this.defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
            Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof BlockItem bi) {
            BlockState skinState = bi.getBlock().defaultBlockState();
            if (skinState.getShape(level, pos) == Shapes.block()) {
                if (level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) {
                    var key = BuiltInRegistries.BLOCK.getKey(bi.getBlock());
                    String newSkin = key.toString();
                    // 拿相同方块右键 → 重置为默认
                    if (newSkin.equals(screen.getSkinBlock())) {
                        screen.setSkinBlock("");
                    } else {
                        screen.setSkinBlock(newSkin);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
        }
        // 非皮肤方块：允许后续 useWithoutItem（空手打开 URL 输入界面）
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (!level.isClientSide()) return InteractionResult.PASS;
        // 主手持有物品（如连接工具）时不打开，交给物品自身逻辑
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        ScreenGuiOpeners.openUrlInput(pos);
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VideoScreenBlockEntity(pos, state);
    }
}
