package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 检测玩家准心瞄准的视频屏幕
 */
public class CrosshairTargetHelper {

    public static VideoScreenBlockEntity getTargetScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        if (mc.level == null) return null;

        if (mc.level.getBlockEntity(hit.getBlockPos()) instanceof VideoScreenBlockEntity be) {
            return be;
        }
        return null;
    }
}
