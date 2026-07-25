package me.zuogeren.kazumiplayer.screen;

/**
 * Phase 0: Placeholder render state for VideoScreenBlockEntity.
 * Will be expanded in Phase 4 with actual render state fields.
 */
public class VideoScreenRenderState extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState {
    public float screenWidth = 3.0f;
    public float screenHeight = 2.0f;
    public net.minecraft.core.Direction facing = net.minecraft.core.Direction.NORTH;
    public VideoState videoState = VideoState.IDLE;
}
