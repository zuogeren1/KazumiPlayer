package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.network.packet.RemoteOpenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * 屏幕遥控器：右键远程打开绑定屏幕的播放器 GUI。
 */
public class ScreenRemoteItem extends AbstractScreenRemoteItem {

    public ScreenRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    protected void openRemote(BlockPos target) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            conn.send(new ServerboundCustomPayloadPacket(new RemoteOpenPacket(target)));
        }
    }
}
