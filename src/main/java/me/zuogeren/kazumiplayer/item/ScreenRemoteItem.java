package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.network.ClientPacketSender;
import me.zuogeren.kazumiplayer.network.packet.RemoteOpenPacket;
import net.minecraft.core.BlockPos;

/**
 * 屏幕遥控器：右键远程打开绑定屏幕的播放器 GUI。
 */
public class ScreenRemoteItem extends AbstractScreenRemoteItem {

    public ScreenRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    protected void openRemote(BlockPos target) {
        ClientPacketSender.send(new RemoteOpenPacket(target));
    }
}
