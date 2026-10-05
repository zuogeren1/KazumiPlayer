package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.ClientConfig;

/**
 * B 站凭据的两个来源与优先级：
 * <ul>
 *   <li><b>本地</b>（客户端配置 {@code bilibiliCookie}）：扫码登录写入，或从浏览器复制粘贴；</li>
 *   <li><b>服务端</b>：服务端配置经 {@code BilibiliCookiePacket} 登录时下发。</li>
 * </ul>
 * 本地有值时优先（个人账号仅存本机，不回传服务端），否则回落到服务端下发的共享凭据。
 * 自研解析（BilibiliApi）与 WaterMedia 平台配置注入都取 {@link #get()}。
 */
public final class BilibiliCredentials {

    private static volatile String serverCookie = "";

    private BilibiliCredentials() {}

    /** 服务端下发的凭据（登录时由 {@code BilibiliCookiePacket} 写入；空串表示服务端未配置） */
    public static void setServer(String value) {
        serverCookie = normalize(value);
    }

    /** 本地配置的凭据（扫码登录后由配置界面写盘） */
    public static String local() {
        try {
            return normalize(ClientConfig.CONFIG.bilibiliCookie.get());
        } catch (Throwable t) {
            return ""; // 配置尚未加载（模组初始化早期）：按未配置处理
        }
    }

    /** 生效凭据：本地优先，否则服务端下发 */
    public static String get() {
        String local = local();
        return local.isEmpty() ? serverCookie : local;
    }

    /** 是否已有可用凭据（任一来源） */
    public static boolean present() {
        return !get().isEmpty();
    }

    /** 本地是否已登录（用于配置界面与登录界面展示状态） */
    public static boolean localPresent() {
        return !local().isEmpty();
    }

    /**
     * 把生效凭据注入 WaterMedia 平台配置（内置 BiliBiliPlatform 每次请求实时读取该字段）。
     * 本地配置变更（扫码登录/手工编辑）与服务端下发后调用，保持两条解析链路凭据一致。
     */
    public static void applyToWaterMedia() {
        try {
            org.watermedia.WaterMediaConfig.platforms.biliBiliCookie = get();
        } catch (Throwable t) {
            me.zuogeren.kazumiplayer.util.KazumiLog.sniff.warn(
                "Failed to apply Bilibili cookie to WaterMedia: {}", String.valueOf(t.getMessage()));
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
