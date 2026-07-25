package me.zuogeren.kazumiplayer.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import me.zuogeren.kazumiplayer.KazumiPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Objects;

public class VideoScreenRenderer implements BlockEntityRenderer<VideoScreenBlockEntity, VideoScreenRenderState> {

    public VideoScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public VideoScreenRenderState createRenderState() {
        return new VideoScreenRenderState();
    }

    @Override
    public void extractRenderState(
            VideoScreenBlockEntity be,
            VideoScreenRenderState state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@org.jspecify.annotations.Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderState.extractBase(be, state, breakProgress);
        state.screenWidth = be.getScreenWidth();
        state.screenHeight = be.getScreenHeight();
        state.facing = be.getFacing();
        state.videoState = be.getVideoState();
    }

    @Override
    public void submit(VideoScreenRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        // Phase 0: Placeholder rendering -- just a colored quad
        // Phase 4 will render WaterMedia video texture here
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }
}
