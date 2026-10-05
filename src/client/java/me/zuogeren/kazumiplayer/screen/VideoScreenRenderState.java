package me.zuogeren.kazumiplayer.screen;

import net.minecraft.client.renderer.item.ItemStackRenderState;

public class VideoScreenRenderState extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState {
    public float screenWidth = 3.0f;
    public float screenHeight = 2.0f;
    public float offsetX = 0.0f;
    public float offsetY = 0.0f;
    public float offsetZ = 0.0f;
    public net.minecraft.core.Direction facing = net.minecraft.core.Direction.NORTH;
    public VideoState videoState = VideoState.IDLE;
    public me.zuogeren.kazumiplayer.playback.WaterMediaPlayer player;
    public String skinBlock = "";
    public ItemStackRenderState skinItemState;
    public VideoScreenTexture videoTexture; // 每屏幕独立纹理
    public String episodeUrl = "";        // 当前集 URL（空=未在播），等待提示的判定依据
    public String resolveStates = "";     // 观看者解析状态 NBT（服务端聚合，markDirty 同步）
    public String watchingPlayers = "";   // 观看者 UUID 列表（本端是否参与该屏解析/等待的判定依据）
}
