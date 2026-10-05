package me.zuogeren.kazumiplayer.client;

/**
 * 客户端持有的 B 站凭据（服务端配置经 BilibiliCookiePacket 登录时下发）。
 * 自研解析（BilibiliApi）在请求头附加该 Cookie 以取得更高清晰度；
 * 未配置时为空串，按未登录请求（html5 播放接口上限 720P）。
 */
public final class BilibiliCredentials {

    private static volatile String cookie = "";

    private BilibiliCredentials() {}

    public static void set(String value) {
        cookie = value == null ? "" : value.trim();
    }

    public static String get() {
        return cookie;
    }

    public static boolean present() {
        return !cookie.isEmpty();
    }
}
