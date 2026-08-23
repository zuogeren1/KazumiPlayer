package me.zuogeren.kazumiplayer.speaker;

import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
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

import java.util.UUID;

/**
 * 音响方块实体（共享代码，只保存服务端连接状态）。
 * 客户端音频播放逻辑在客户端模块的 SpeakerClientAudio 中。
 */
public class SpeakerBlockEntity extends BlockEntity {

    // 连接状态
    private UUID linkedScreenId;
    private BlockPos linkedScreenPos;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(SpeakerRegistration.SPEAKER_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getLinkedScreenId() { return linkedScreenId; }
    public BlockPos getLinkedScreenPos() { return linkedScreenPos; }

    public void setLink(UUID screenId, BlockPos screenPos) {
        this.linkedScreenId = screenId;
        this.linkedScreenPos = screenPos;
        markDirty();
    }

    public void clearLink() {
        this.linkedScreenId = null;
        this.linkedScreenPos = null;
        markDirty();
    }

    public boolean isLinked() {
        return linkedScreenId != null && linkedScreenPos != null;
    }

    /** 根据已保存的连接信息获取屏幕 BE（可能为 null） */
    @Nullable
    public VideoScreenBlockEntity getLinkedScreen() {
        if (level == null || linkedScreenPos == null) return null;
        var be = level.getBlockEntity(linkedScreenPos);
        if (be instanceof VideoScreenBlockEntity screen
                && linkedScreenId != null
                && linkedScreenId.equals(screen.getScreenId())) {
            return screen;
        }
        return null;
    }

    // ---- NBT ----

    @Override
    protected void loadAdditional(ValueInput input) {
        this.linkedScreenId = input.getString("LinkedScreenId")
            .filter(s -> !s.isEmpty()).map(UUID::fromString).orElse(null);
        this.linkedScreenPos = input.read("LinkedScreenPos", net.minecraft.core.BlockPos.CODEC).orElse(null);
        // 注意：此处不做"屏幕不存在即清连接"的防御——加载顺序不可靠，
        // 屏幕 chunk 未加载时 getBlockEntity 返回 null，会误删有效连接并写盘。
        // 合法失效路径均已有清理：屏幕破坏 setRemoved 遍历 ConnectedSpeakers 清 link、
        // 音响挖除 setRemoved 反向通知、手动断开双向清理。
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putString("LinkedScreenId", linkedScreenId != null ? linkedScreenId.toString() : "");
        if (linkedScreenPos != null) {
            output.store("LinkedScreenPos", net.minecraft.core.BlockPos.CODEC, linkedScreenPos);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("LinkedScreenId", linkedScreenId != null ? linkedScreenId.toString() : "");
        if (linkedScreenPos != null) {
            tag.putInt("LinkedScreenX", linkedScreenPos.getX());
            tag.putInt("LinkedScreenY", linkedScreenPos.getY());
            tag.putInt("LinkedScreenZ", linkedScreenPos.getZ());
        }
        return tag;
    }

    private void markDirty() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 服务端：通知屏幕清理反向索引
        if (level != null && !level.isClientSide() && isLinked()) {
            var be = getLinkedScreen();
            if (be instanceof VideoScreenBlockEntity screen) {
                screen.removeConnectedSpeaker(worldPosition);
            }
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
