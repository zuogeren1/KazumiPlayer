package me.zuogeren.kazumiplayer.screen;

import net.minecraft.client.renderer.item.ItemStackRenderState;

public class VideoScreenRenderState extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState {
    public float screenWidth = 3.0f;
    public float screenHeight = 2.0f;
    public net.minecraft.core.Direction facing = net.minecraft.core.Direction.NORTH;
    public VideoState videoState = VideoState.IDLE;
    public me.zuogeren.kazumiplayer.playback.WaterMediaPlayer player;
    public String skinBlock = "";
    public ItemStackRenderState skinItemState;
}
