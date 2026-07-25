package me.zuogeren.kazumiplayer.util;

import java.net.URI;

/**
 * 剧集 URL 标准化, 移植自 Kazumi Dart 的 normalizeEpisodeUrl
 */
public class EpisodeUrlNormalizer {

    /**
     * 将相对 URL 解析为基于 baseUrl 的绝对 URL, 并统一下协议
     */
    public static String normalize(String baseUrl, String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";

        try {
            URI rawUri = URI.create(trimmed);
            URI baseUri = URI.create(baseUrl);

            URI resolved;
            if (rawUri.isAbsolute()) {
                resolved = rawUri;
            } else {
                resolved = baseUri.resolve(trimmed);
            }

            // 协议统一: 如果 host 相同, 使用 baseUrl 的 scheme
            if (resolved.getHost() != null
                    && resolved.getHost().equals(baseUri.getHost())
                    && baseUri.getScheme() != null) {
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
            // 解析失败时返回原始值
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed;
            }
            if (baseUrl.endsWith("/")) {
                return baseUrl + trimmed;
            }
            return baseUrl + "/" + trimmed;
        }
    }
}
