package me.zuogeren.kazumiplayer.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 剧集 URL 标准化, 移植自 Kazumi Dart 的 normalizeEpisodeUrl (utils/episode_url.dart)。
 *
 * 与 Dart 的差异点（Java URI 更严格，需额外兜底）：
 * - 绝对 URL 判定要求 host 非空（javascript:void(0) 等 opaque URI 在 Java 中
 *   isAbsolute()==true 但无 host，不能当绝对 URL 放行）；
 * - URI.create 对中文/空格等未编码字符抛异常，先 percent 编码后重试 resolve。
 */
public class EpisodeUrlNormalizer {

    /**
     * 将相对 URL 解析为基于 baseUrl 的绝对 URL, 并统一下协议
     */
    public static String normalize(String baseUrl, String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";

        URI rawUri = tryParse(trimmed);
        URI baseUri = tryParse(baseUrl);
        boolean validBase = baseUri != null && baseUri.getScheme() != null
                && baseUri.getHost() != null && !baseUri.getHost().isEmpty();

        URI resolved;
        if (rawUri != null && rawUri.isAbsolute()
                && rawUri.getHost() != null && !rawUri.getHost().isEmpty()) {
            resolved = rawUri;
        } else if (validBase) {
            resolved = resolveLenient(baseUri, trimmed);
        } else {
            resolved = null;
        }

        // 无法解析为绝对 URL（含 javascript:/mailto: 等无 host 的伪绝对地址），原样返回
        if (resolved == null || resolved.getHost() == null || resolved.getHost().isEmpty()) {
            return trimmed;
        }

        try {
            // 协议统一: 同 host 时使用 baseUrl 的 scheme
            if (resolved.getHost().equals(baseUri.getHost()) && baseUri.getScheme() != null) {
                resolved = new URI(
                        baseUri.getScheme(),
                        resolved.getUserInfo(),
                        resolved.getHost(),
                        resolved.getPort(),
                        resolved.getPath(),
                        resolved.getQuery(),
                        resolved.getFragment());
            }

            // 移除路径尾部斜杠
            String path = resolved.getPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
                resolved = new URI(
                        resolved.getScheme(),
                        resolved.getUserInfo(),
                        resolved.getHost(),
                        resolved.getPort(),
                        path,
                        resolved.getQuery(),
                        resolved.getFragment());
            }

            return resolved.toString();
        } catch (Exception e) {
            // 归一化失败时返回原始值，不改变端点指向
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed;
            }
            if (baseUrl.endsWith("/")) {
                return baseUrl + trimmed;
            }
            return baseUrl + "/" + trimmed;
        }
    }

    private static URI tryParse(String s) {
        try {
            return URI.create(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 宽容 resolve：href 含未编码非法字符（中文/空格/竖线等站点常见写法）时，
     * percent 编码非法字节后重试（URI.resolve(String) 内部 create 失败会直接抛）。
     * 重试仍失败返回 null，由调用方按"无法解析"处理，异常不得逃出 normalize()。
     */
    private static URI resolveLenient(URI base, String ref) {
        try {
            return base.resolve(ref);
        } catch (Exception e) {
            try {
                return base.resolve(encodeIllegalChars(ref));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** 保留 RFC 3986 合法字符与已有的百分号编码，其余字节按 UTF-8 percent 编码 */
    private static String encodeIllegalChars(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (byte b : escapeStrayPercent(s).getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c == '%') {
                sb.append(c); // 已有百分号编码原样保留，避免双重编码
            } else if (c < 0x80 && (Character.isLetterOrDigit(c)
                    || "-._~:/?#[]@!$&'()*+,;=".indexOf(c) >= 0)) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    /** 孤立 %（后两个字符非 hex）编码为 %25：如 "50%off.html"，否则 resolve 双次失败且异常逃逸 */
    private static String escapeStrayPercent(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean validEscape = c == '%'
                    && i + 2 < s.length()
                    && isHexDigit(s.charAt(i + 1)) && isHexDigit(s.charAt(i + 2));
            if (c == '%' && !validEscape) {
                sb.append("%25");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
