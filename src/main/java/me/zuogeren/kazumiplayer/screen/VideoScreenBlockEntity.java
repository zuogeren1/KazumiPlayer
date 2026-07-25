package me.zuogeren.kazumiplayer.screen;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

public class VideoScreenBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();

    private float screenWidth = 3.0f;
    private float screenHeight = 2.0f;
    private Direction facing = Direction.NORTH;
    private VideoState videoState = VideoState.IDLE;

    // NBT 持久化：播放 URL 和同步位置
    private String episodeUrl = "";
    private long syncPositionMs = -1;

    // 客户端暂存，不持久化
    public transient me.zuogeren.kazumiplayer.playback.WaterMediaPlayer player;

    public VideoScreenBlockEntity(BlockPos pos, BlockState blockState) {
        super(VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public float getScreenWidth() { return screenWidth; }
    public float getScreenHeight() { return screenHeight; }
    public Direction getFacing() { return facing; }
    public VideoState getVideoState() { return videoState; }
    public String getEpisodeUrl() { return episodeUrl; }
    public long getSyncPositionMs() { return syncPositionMs; }

    public void setScreenSize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
        markDirty();
    }

    public void setFacing(Direction facing) {
        this.facing = facing;
        markDirty();
    }

    public void setVideoState(VideoState state) {
        this.videoState = state;
        markDirty();
    }

    /** 服务端：设置播放 URL 和同步位置，触发 NBT 同步 */
    public void setPlayback(String url, long positionMs) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        this.videoState = VideoState.PLAYING;
        markDirty();
        LOGGER.info("setPlayback url={} posMs={} side={}", url, positionMs,
            level != null && level.isClientSide() ? "client" : "server");
    }

    /** 服务端/客户端：清除播放状态（保留计时用于断线重连） */
    public void clearPlayback() {
        this.episodeUrl = "";
        this.videoState = VideoState.IDLE;
        if (player != null) {
            player.stop();
            player = null;
        }
        markDirty();
        LOGGER.info("clearPlayback side={} (kept posMs={})",
            level != null && level.isClientSide() ? "client" : "server", syncPositionMs);
    }

    /** 仅更新同步位置（不清除 URL） */
    public void updateSyncPosition(long positionMs) {
        this.syncPositionMs = positionMs;
        markDirty();
    }

    private void markDirty() {
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
        this.episodeUrl = input.getString("EpisodeUrl").orElse("");
        this.syncPositionMs = input.getLongOr("SyncPositionMs", -1L);

        // 客户端：NBT 中 URL 被清空 → 停止播放器
        if (level != null && level.isClientSide() && episodeUrl.isEmpty() && player != null) {
            player.stop();
            player = null;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putFloat("ScreenWidth", screenWidth);
        output.putFloat("ScreenHeight", screenHeight);
        output.putString("Facing", facing.getName());
        output.putString("VideoState", videoState.name());
        output.putString("EpisodeUrl", episodeUrl);
        output.putLong("SyncPositionMs", syncPositionMs);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("ScreenWidth", screenWidth);
        tag.putFloat("ScreenHeight", screenHeight);
        tag.putString("Facing", facing.getName());
        tag.putString("VideoState", videoState.name());
        tag.putString("EpisodeUrl", episodeUrl);
        tag.putLong("SyncPositionMs", syncPositionMs);
        return tag;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (player != null && level != null && level.isClientSide()) {
            player.stop();
            player = null;
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
