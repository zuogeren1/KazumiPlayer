package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.network.packet.RemoteFullscreenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * 远程观影器：右键跳过 GUI 直接进入绑定屏幕的全屏观影（ClientFullscreenState），
 * 纯观看——不提供任何播放控制操作。
 */
public class RemoteViewerItem extends AbstractScreenRemoteItem {

    public RemoteViewerItem(Properties properties) {
        super(properties);
    }

    @Override
    protected void openRemote(BlockPos target) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            conn.send(new ServerboundCustomPayloadPacket(new RemoteFullscreenPacket(target)));
        }
    }
}
