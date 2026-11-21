package me.zuogeren.kazumiplayer.util;

/**
 * 抖音链接识别（纯字符串判定，零网络访问）。
 * 播放侧据此绕开 Rinku 嗅探：抖音网页是 JS VM 保护的壳（实测分享页 72KB 无数据、
 * `aweme/v1/web/` 直接 403 ArgusSecurityPlugin），嗅探浏览器拿不到可用直链；
 * 点播改走 {@code api.amemv.com} 详情接口（实测 200 + 完整 aweme_detail）。
 *
 * <p>支持的输入形态：
 * <ul>
 *   <li>短链 {@code https://v.douyin.com/xxxxx/}（需先跟随重定向）</li>
 *   <li>作品页 {@code https://www.douyin.com/video/<aweme_id>}</li>
 *   <li>图文页 {@code https://www.douyin.com/note/<id>}（无视频，解析会明确报错）</li>
 *   <li>分享页 {@code https://www.iesdouyin.com/share/video/<aweme_id>/}</li>
 *   <li>整段分享口令（含中文与 emoji，链接嵌在文本里）</li>
 * </ul>
 */
public final class DouyinUrls {

    private static final String SHORT_DOMAIN = "v.douyin.com";
    private static final String MAIN_DOMAIN = "douyin.com";
    private static final String IES_DOMAIN = "iesdouyin.com";

    /** 从整段分享口令里提取第一个 http(s) 链接 */
    private static final java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile(
        "https?://[\\w.-]+(?:/[\\w./?%&=+#@~-]*)?",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    private DouyinUrls() {}

    /**
     * 从任意文本（分享口令/纯链接）里提取第一个 URL；没有则返回 null。
     * 抖音分享口令形如「7.68 复制打开抖音，看看【xxx】的作品 https://v.douyin.com/xxxxx/ ...」，
     * 直接拿整段去请求必然失败，必须先摘出链接。
     */
    public static String extractUrl(String text) {
        if (text == null) return null;
        var m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** 是否为抖音链接（短链 / 作品页 / 图文页 / 分享页） */
    public static boolean isDouyinUrl(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        return matches(host, SHORT_DOMAIN) || matches(host, MAIN_DOMAIN) || matches(host, IES_DOMAIN);
    }

    /** v.douyin.com 短链：需先跟随重定向拿到真实页面 URL 再判定类型 */
    public static boolean isShortLink(String url) {
        String host = hostOf(url);
        return host != null && matches(host, SHORT_DOMAIN);
    }

    /** 图文页（/note/<id>）：抖音图文没有视频流，解析侧据此给出可读原因 */
    public static boolean isNotePage(String url) {
        String path = pathOf(url).toLowerCase();
        return path.startsWith("/note/") || path.startsWith("/share/note/");
    }

    /**
     * 从作品页/分享页 URL 里取 aweme_id。
     * 依次尝试：query 参数（modal_id / vid / id）→ 路径末段（/video/<id>、/share/video/<id>）。
     * 取不到返回 null。
     */
    public static String awemeIdOf(String url) {
        if (url == null) return null;
        String query = queryOf(url);
        if (query != null) {
            for (String key : new String[]{"modal_id", "vid", "id"}) {
                String v = queryParam(query, key);
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        String path = pathOf(url);
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) continue;
            if (seg.endsWith(".html")) seg = seg.substring(0, seg.length() - 5);
            if (seg.matches("[1-9][0-9]{15,19}")) return seg;
        }
        return null;
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

    /** 取 query 串（不含 '?'），无则返回 null */
    private static String queryOf(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        return query.isEmpty() ? null : query;
    }

    /** 从 query 串里取参数值（已 URL 解码）；未命中返回 null */
    private static String queryParam(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!pair.substring(0, eq).equals(key)) continue;
            String raw = pair.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return null;
    }
}
