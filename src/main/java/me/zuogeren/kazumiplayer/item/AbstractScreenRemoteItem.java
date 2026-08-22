package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.util.KazumiMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 屏幕遥控器类物品的公共骨架：
 * - Shift+右键屏幕方块 → 绑定该屏幕位置（存于物品 CUSTOM_DATA）
 * - 手持右键任意处 → {@link #openRemote}（子类决定打开 GUI 还是直接全屏）
 * - Shift+右键空中 → 清除绑定
 */
public abstract class AbstractScreenRemoteItem extends Item {

    protected AbstractScreenRemoteItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS; // 非 shift 让屏幕正常交互
        if (level.getBlockEntity(ctx.getClickedPos()) instanceof VideoScreenBlockEntity screen) {
            BlockPos pos = screen.getBlockPos();
            CompoundTag tag = new CompoundTag();
            tag.putInt("TargetX", pos.getX());
            tag.putInt("TargetY", pos.getY());
            tag.putInt("TargetZ", pos.getZ());
            CustomData.set(DataComponents.CUSTOM_DATA, ctx.getItemInHand(), tag);
            if (level.isClientSide()) {
                KazumiMessages.sendSuccess(player,
                    "已绑定屏幕 (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            stack.remove(DataComponents.CUSTOM_DATA);
            if (level.isClientSide()) {
                KazumiMessages.sendWarn(player, "已清除绑定的屏幕");
            }
            return InteractionResult.SUCCESS;
        }
        BlockPos target = readBinding(stack);
        if (target == null) {
            if (level.isClientSide()) {
                KazumiMessages.sendInfo(player, "请先 Shift+右键一个屏幕方块进行绑定");
            }
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            openRemote(target);
        }
        return InteractionResult.SUCCESS;
    }

    /** 对绑定的远程屏幕执行动作（仅客户端调用；动作须经服务端校验后生效） */
    protected abstract void openRemote(BlockPos target);

    private static BlockPos readBinding(ItemStack stack) {
        var cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains("TargetX") || !tag.contains("TargetY") || !tag.contains("TargetZ")) return null;
        return new BlockPos(
            tag.getInt("TargetX").orElse(0),
            tag.getInt("TargetY").orElse(0),
            tag.getInt("TargetZ").orElse(0));
    }
}
