package me.zuogeren.kazumiplayer.screen;
import me.zuogeren.kazumiplayer.util.KazumiLog;

import net.minecraft.core.BlockPos;
import java.util.UUID;
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
import java.util.ArrayList;
import java.util.List;

public class VideoScreenBlockEntity extends BlockEntity {

    private float screenWidth = 3.0f;
    private float screenHeight = 2.0f;
    private Direction facing = Direction.NORTH;
    private VideoState videoState = VideoState.IDLE;

    // 播放状态
    private String episodeUrl = "";
    private long syncPositionMs = -1;

    // 集数管理
    private int episodeIndex = 1;          // 当前集数 (1-based)
    private int roadIndex = 0;             // 当前线路 (0-based，指向 episodeData 中的 Road)
    private String episodeData = "";       // Road JSON（所有集的名称+URL）
    private String playingTitle = "";      // 番剧名称（规则播放时写入；直链播放保持空，GUI 不显示"正在播放"行）
    private String watchingPlayers = "";   // 观看者 UUID 列表，逗号分隔
    private boolean playbackPaused;
    private String skinBlock = "";         // 方块皮肤 ID，空=默认
    private UUID screenId;                 // 屏幕唯一标识，lazy 生成
    private final List<BlockPos> connectedSpeakers = new ArrayList<>(); // 已连接音响列表

    public VideoScreenBlockEntity(BlockPos pos, BlockState blockState) {
        super(VideoScreenRegistration.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public float getScreenWidth() { return screenWidth; }
    public float getScreenHeight() { return screenHeight; }
    public Direction getFacing() { return facing; }
    public VideoState getVideoState() { return videoState; }
    public String getEpisodeUrl() { return episodeUrl; }
    public long getSyncPositionMs() { return syncPositionMs; }
    public int getEpisodeIndex() { return episodeIndex; }
    public int getRoadIndex() { return roadIndex; }
    public String getEpisodeData() { return episodeData; }
    public String getPlayingTitle() { return playingTitle; }
    public void setPlayingTitle(String title) {
        this.playingTitle = title == null ? "" : title;
        markDirty();
    }
    public String getWatchingPlayers() { return watchingPlayers; }
    public boolean isPlaybackPaused() { return playbackPaused; }
    public String getSkinBlock() { return skinBlock; }
    public List<BlockPos> getConnectedSpeakers() { return connectedSpeakers; }
    public void addConnectedSpeaker(BlockPos pos) {
        if (!connectedSpeakers.contains(pos)) { connectedSpeakers.add(pos); markDirty(); }
    }
    public void removeConnectedSpeaker(BlockPos pos) {
        connectedSpeakers.remove(pos); markDirty();
    }
    /** 屏幕唯一标识，首次访问时 lazy 生成 */
    public UUID getScreenId() {
        if (screenId == null) {
            screenId = UUID.randomUUID();
            markDirty();
        }
        return screenId;
    }

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

    public void setPlayback(String url, long positionMs) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        // 空 URL 视为未播放：状态保持 IDLE，不残留 SyncPositionMs
        this.videoState = url.isEmpty() ? VideoState.IDLE : VideoState.PLAYING;
        if (url.isEmpty()) this.syncPositionMs = -1;
        markDirty();
        KazumiLog.screen.info("setPlayback url={} posMs={} side={}", url, positionMs,
            level != null && level.isClientSide() ? "client" : "server");
    }

    /** 设置完整播放信息（含线路、集数和 Road 数据） */
    public void setPlaybackFull(String url, long positionMs, int roadIdx, int episodeIdx, String episodeDataJson) {
        this.episodeUrl = url;
        this.syncPositionMs = positionMs;
        this.roadIndex = roadIdx;
        this.episodeIndex = episodeIdx;
        this.episodeData = episodeDataJson;
        this.videoState = VideoState.PLAYING;
        markDirty();
    }

    public void clearPlayback() {
        this.episodeUrl = "";
        this.syncPositionMs = -1;
        this.episodeIndex = 1;
        this.roadIndex = 0;
        this.episodeData = "";
        this.playingTitle = "";
        this.videoState = VideoState.IDLE;
        markDirty();
        KazumiLog.screen.info("clearPlayback side={}",
            level != null && level.isClientSide() ? "client" : "server");
    }

    public void updateSyncPosition(long positionMs) {
        this.syncPositionMs = positionMs;
        markDirty();
    }

    /** 只更新剧集/队列数据（如队列插队、移除项），不动当前播放状态 */
    public void setEpisodeData(String episodeDataJson) {
        this.episodeData = episodeDataJson == null ? "" : episodeDataJson;
        markDirty();
    }

    /** 只更新当前项序号（1-based），如队列移除待播区之前项后的序号修正 */
    public void setEpisodeIndex(int index) {
        this.episodeIndex = index;
        markDirty();
    }

    public void setWatchingPlayers(String players) {
        this.watchingPlayers = players;
        markDirty();
    }

    public void setPlaybackPaused(boolean paused) {
        this.playbackPaused = paused;
        markDirty();
    }

    public void setSkinBlock(String id) {
        this.skinBlock = id;
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
        this.episodeIndex = input.getIntOr("EpisodeIndex", 1);
        this.roadIndex = input.getIntOr("RoadIndex", 0);
        this.episodeData = input.getString("EpisodeData").orElse("");
        this.playingTitle = input.getString("PlayingTitle").orElse("");
        this.watchingPlayers = input.getString("WatchingPlayers").orElse("");
        this.playbackPaused = input.getBooleanOr("PlaybackPaused", false);
        this.skinBlock = input.getString("SkinBlock").orElse("");
        this.screenId = input.getString("ScreenId").filter(s -> !s.isEmpty()).map(UUID::fromString).orElse(null);
        // ConnectedSpeakers: 逗号分隔的 BlockPos 编码 "x,y,z;x,y,z;..."
        this.connectedSpeakers.clear();
        input.getString("ConnectedSpeakers").ifPresent(str -> {
            for (String s : str.split(";")) {
                if (s.isBlank()) continue;
                String[] p = s.split(",");
                if (p.length == 3) {
                    try {
                        connectedSpeakers.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                    } catch (NumberFormatException ignored) {}
                }
            }
        });

    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putFloat("ScreenWidth", screenWidth);
        output.putFloat("ScreenHeight", screenHeight);
        output.putString("Facing", facing.getName());
        output.putString("VideoState", videoState.name());
        output.putString("EpisodeUrl", episodeUrl);
        output.putLong("SyncPositionMs", syncPositionMs);
        output.putInt("EpisodeIndex", episodeIndex);
        output.putInt("RoadIndex", roadIndex);
        output.putString("EpisodeData", episodeData);
        output.putString("PlayingTitle", playingTitle);
        output.putString("WatchingPlayers", watchingPlayers);
        output.putBoolean("PlaybackPaused", playbackPaused);
        output.putString("SkinBlock", skinBlock);
        output.putString("ScreenId", screenId != null ? screenId.toString() : "");
        // ConnectedSpeakers: "x,y,z;x,y,z;..."
        var sb = new StringBuilder();
        for (var pos : connectedSpeakers) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        output.putString("ConnectedSpeakers", sb.toString());
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
        tag.putInt("EpisodeIndex", episodeIndex);
        tag.putInt("RoadIndex", roadIndex);
        tag.putString("EpisodeData", episodeData);
        tag.putString("PlayingTitle", playingTitle);
        tag.putString("WatchingPlayers", watchingPlayers);
        tag.putBoolean("PlaybackPaused", playbackPaused);
        tag.putString("SkinBlock", skinBlock);
        tag.putString("ScreenId", screenId != null ? screenId.toString() : "");
        var sb = new StringBuilder();
        for (var pos : connectedSpeakers) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        tag.putString("ConnectedSpeakers", sb.toString());
        return tag;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 服务端：屏幕被破坏时清理 SyncGroup + 通知所有已连接音响
        if (level != null && !level.isClientSide()) {
            if (screenId != null) {
                ScreenRemovalListeners.dispatch(screenId, worldPosition);
            }
            // 通知音响清空连接
            for (BlockPos spkPos : new ArrayList<>(connectedSpeakers)) {
                var be = level.getBlockEntity(spkPos);
                if (be instanceof me.zuogeren.kazumiplayer.speaker.SpeakerBlockEntity spk) {
                    spk.clearLink();
                }
            }
            connectedSpeakers.clear();
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
