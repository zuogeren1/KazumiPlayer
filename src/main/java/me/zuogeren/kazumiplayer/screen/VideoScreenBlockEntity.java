package me.zuogeren.kazumiplayer.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class VideoScreenBlockEntity extends BlockEntity {
    private float screenWidth = 3.0f;
    private float screenHeight = 2.0f;
    private Direction facing = Direction.NORTH;
    private VideoState videoState = VideoState.IDLE;
    // 客户端暂存，不持久化
    public transient me.zuogeren.kazumiplayer.playback.WaterMediaPlayer player;

    public VideoScreenBlockEntity(BlockPos pos, BlockState blockState) {
        super(VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public float getScreenWidth() { return screenWidth; }
    public float getScreenHeight() { return screenHeight; }
    public Direction getFacing() { return facing; }
    public VideoState getVideoState() { return videoState; }

    public void setScreenSize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setFacing(Direction facing) {
        this.facing = facing;
        setChanged();
    }

    public void setVideoState(VideoState state) {
        this.videoState = state;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        this.screenWidth = input.getFloatOr("ScreenWidth", 3.0f);
        this.screenHeight = input.getFloatOr("ScreenHeight", 2.0f);
        this.facing = input.getString("Facing").map(Direction::byName).orElse(Direction.NORTH);
        this.videoState = input.getString("VideoState").map(VideoState::fromName).orElse(VideoState.IDLE);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putFloat("ScreenWidth", screenWidth);
        output.putFloat("ScreenHeight", screenHeight);
        output.putString("Facing", facing.getName());
        output.putString("VideoState", videoState.name());
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("ScreenWidth", screenWidth);
        tag.putFloat("ScreenHeight", screenHeight);
        tag.putString("Facing", facing.getName());
        tag.putString("VideoState", videoState.name());
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
