package me.zuogeren.kazumiplayer.network.gui;

import me.zuogeren.kazumiplayer.util.JsonUtil;

import java.util.List;

/**
 * GUI 通用通道的 payload DTO（Gson 序列化，字段必须 public，禁 private——见 CLAUDE.md）。
 * 服务端与客户端共用同一形状。
 */
public final class GuiPayloads {

    private GuiPayloads() {}

    // ---- C→S 请求 ----

    public record SearchBangumiPayload(String keyword) {}

    /** rule 为空字符串表示在全部规则中搜索 */
    public record SearchRulePayload(String rule, String keyword) {}

    public record QueryChaptersPayload(String rule, String id) {}

    public record PlayEpisodePayload(String rule, String id, int episode) {}

    // ---- S→C 响应 ----

    public record BangumiResultItem(String name, String date, String summary) {}

    public record RuleResultItem(String rule, String id, String name) {}

    public record ChaptersPayload(List<String> names, int total) {}

    public record PlayOkPayload(String title) {}

    public record ErrorPayload(String message) {}

    // ---- 编解码辅助 ----

    public static String toJson(Object payload) {
        return JsonUtil.GSON.toJson(payload);
    }

    public static <T> T fromJson(String json, Class<T> cls) {
        return JsonUtil.GSON.fromJson(json, cls);
    }

    /** 泛型集合反序列化：fromJson(json, new TypeToken<List<T>>(){}.getType()) */
    public static <T> T fromJson(String json, java.lang.reflect.Type type) {
        return JsonUtil.GSON.fromJson(json, type);
    }
}
