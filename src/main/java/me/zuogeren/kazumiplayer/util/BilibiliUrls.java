package me.zuogeren.kazumiplayer.util;

/**
 * B 站链接识别（纯字符串判定，零网络访问）。
 * 播放侧据此绕开 Rinku 嗅探：B 站页面链接交给服务端代理调用 B 站接口解析
 * （见 network/packet/BilibiliResolve*，凭据留在服务端），失败时本端凭据回落；
 * 嗅探浏览器对 B 站播放页拿不到可用直链。
 */
public final class BilibiliUrls {

    private static final String SHORT_DOMAIN = "b23.tv";
    private static final String MAIN_DOMAIN = "bilibili.com";
    private static final String LIVE_HOST = "live.bilibili.com";

    private BilibiliUrls() {}

    /** 是否为 B 站链接（视频页 / 直播间 / 短链） */
    public static boolean isBilibiliUrl(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        return matches(host, SHORT_DOMAIN) || matches(host, MAIN_DOMAIN);
    }

    /** 直播间链接（live.bilibili.com/<房间号>） */
    public static boolean isLiveRoom(String url) {
        String host = hostOf(url);
        return host != null && host.equals(LIVE_HOST);
    }

    /** b23.tv 短链：需先跟随重定向拿到真实页面 URL 再判定类型 */
    public static boolean isShortLink(String url) {
        String host = hostOf(url);
        return host != null && matches(host, SHORT_DOMAIN);
    }

    /** 视频页（bilibili.com/video/BV… 或 /video/av…，可带 ?p= 分 P 参数） */
    public static boolean isVideoPage(String url) {
        String host = hostOf(url);
        if (host == null || !matches(host, MAIN_DOMAIN)) return false;
        String path = pathOf(url).toLowerCase();
        return path.startsWith("/video/bv") || path.startsWith("/video/av");
    }

    /** 直播链接的规范化形式：补齐 https:// 前缀并转为小写主机名 */
    public static String normalize(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (!s.contains("://")) s = "https://" + s;
        return s;
    }

    /** 主机名精确匹配或为其子域 */
    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** 取小写主机名（去 userinfo 与端口）；缺少协议头或主机名为空返回 null */
    private static String hostOf(String url) {
        if (url == null) return null;
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return null;
        String rest = s.substring(scheme + 3);
        int cut = rest.indexOf('/');
        String host = cut >= 0 ? rest.substring(0, cut) : rest;
        int at = host.indexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.toLowerCase();
        return host.isEmpty() ? null : host;
    }

    /** 取路径部分（不含 query/fragment），无路径返回 "/" */
    private static String pathOf(String url) {
        if (url == null) return "/";
        String s = url.trim();
        int scheme = s.indexOf("://");
        String rest = scheme < 0 ? s : s.substring(scheme + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) return "/";
        String path = rest.substring(slash);
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        return path;
    }
}
