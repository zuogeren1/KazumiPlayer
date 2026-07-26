package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.network.packet.SpeakerConnectPacket;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class ConnectionToolItem extends Item {

    public ConnectionToolItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = ctx.getItemInHand();

        if (level.getBlockEntity(ctx.getClickedPos()) instanceof VideoScreenBlockEntity screen) {
            UUID sid = screen.getScreenId();
            BlockPos pos = screen.getBlockPos();
            CompoundTag tag = new CompoundTag();
            tag.putString("TargetId", sid.toString());
            tag.putInt("TargetX", pos.getX());
            tag.putInt("TargetY", pos.getY());
            tag.putInt("TargetZ", pos.getZ());
            CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
            if (level.isClientSide()) {
                player.sendSystemMessage(Component.literal("§a已选中屏幕 ("
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"));
            }
            return InteractionResult.SUCCESS;
        }

        if (level.getBlockEntity(ctx.getClickedPos()) instanceof SpeakerBlockEntity) {
            CompoundTag tag = readSelection(stack);
            if (tag == null || !tag.contains("TargetId")) {
                if (level.isClientSide()) {
                    player.sendSystemMessage(Component.literal("§c请先右键一个屏幕来选中目标"));
                }
                return InteractionResult.FAIL;
            }
            try {
                String uidStr = tag.getString("TargetId").orElse("");
                if (uidStr.isEmpty()) return InteractionResult.FAIL;
                UUID targetId = UUID.fromString(uidStr);
                int tx = tag.getInt("TargetX").orElse(0);
                int ty = tag.getInt("TargetY").orElse(0);
                int tz = tag.getInt("TargetZ").orElse(0);
                BlockPos targetPos = new BlockPos(tx, ty, tz);
                if (level.isClientSide() && player instanceof ServerPlayer sp) {
                    sp.connection.send(new ServerboundCustomPayloadPacket(
                        new SpeakerConnectPacket(ctx.getClickedPos(), targetPos, targetId, true)));
                }
            } catch (IllegalArgumentException ignored) {}
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
                player.sendSystemMessage(Component.literal("§e已清除选中的屏幕"));
            }
            return InteractionResult.SUCCESS;
        }
        CompoundTag tag = readSelection(stack);
        if (tag != null && tag.contains("TargetX") && level.isClientSide()) {
            player.sendSystemMessage(Component.literal("§a当前已选中屏幕 ("
                + tag.getInt("TargetX").orElse(0) + ", " + tag.getInt("TargetY").orElse(0) + ", " + tag.getInt("TargetZ").orElse(0) + ")"));
        }
        return InteractionResult.PASS;
    }

    private static CompoundTag readSelection(ItemStack stack) {
        var cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd != null ? cd.copyTag() : null;
    }
}
