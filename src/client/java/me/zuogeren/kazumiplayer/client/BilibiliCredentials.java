package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;

/**
 * 客户端本地 B 站凭据（客户端配置 {@code bilibiliCookie}：扫码登录写入或手工粘贴）。
 *
 * 服务端代理解析不经过这里——凭据只留在服务端配置中，不下发到客户端。
 * 本类仅用于「服务端解析失败/超时/未连接」时的本端回落解析：此时用本地凭据自行调 B 站接口，
 * 未配置则匿名请求（清晰度上限 720P）。
 */
public final class BilibiliCredentials {

    private BilibiliCredentials() {}

    /** 生效凭据；未配置时为空串（匿名） */
    public static String get() {
        try {
            String value = ClientConfig.CONFIG.bilibiliCookie.get();
            return value == null ? "" : value.trim();
        } catch (Throwable t) {
            return ""; // 配置尚未加载（模组初始化早期）：按未配置处理
        }
    }

    /** 是否已配置本地凭据 */
    public static boolean present() {
        return !get().isEmpty();
    }
}
