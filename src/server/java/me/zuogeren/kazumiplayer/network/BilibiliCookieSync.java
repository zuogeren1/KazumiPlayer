package me.zuogeren.kazumiplayer.network;

import me.zuogeren.kazumiplayer.ServerConfig;
import me.zuogeren.kazumiplayer.network.packet.BilibiliCookiePacket;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 玩家登录时把服务端配置的 B 站 Cookie 下发到客户端。
 * B 站接口请求实际由客户端发出（WaterMedia 内置平台解析在客户端执行），
 * 服务端填写的凭据必须随登录同步才能生效；未配置时下发空串以清空客户端已注入的值。
 */
public final class BilibiliCookieSync {

    private BilibiliCookieSync() {}

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        String cookie = ServerConfig.CONFIG.bilibiliCookie.get();
        PacketDistributor.sendToPlayer(sp, new BilibiliCookiePacket(cookie));
        KazumiLog.network.debug("Bilibili cookie pushed to {} ({} chars)",
            sp.getName().getString(), cookie == null ? 0 : cookie.length());
    }
}
