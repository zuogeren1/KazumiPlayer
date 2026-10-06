package me.zuogeren.kazumiplayer.network.gui;

import me.zuogeren.kazumiplayer.util.JsonUtil;

import java.util.List;

/**
 * GUI 通用通道的 payload DTO（Gson 序列化，字段必须 public，禁 private）。
 * 服务端与客户端共用同一形状。
 */
public final class GuiPayloads {

    private GuiPayloads() {}

    // ---- C→S 请求 ----

    public record SearchBangumiPayload(String keyword) {}

    /** rule 为空字符串表示在全部规则中搜索 */
    public record SearchRulePayload(String rule, String keyword) {}

    /** road 为 0-based 线路下标，缺省 0（Gson 缺字段时 int 默认 0） */
    public record QueryChaptersPayload(String rule, String id, int road) {}

    public record PlayEpisodePayload(String rule, String id, int episode, int road) {}

    /**
     * 屏幕几何属性（ACTION_SCREEN_PROPS 请求与 DATA_SCREEN_PROPS 回显共用）：
     * facing 为 north/south/east/west；width/height 单位格；offset XYZ 为相对默认位置的自由偏移（格）
     */
    public record ScreenPropsPayload(float offsetX, float offsetY, float offsetZ,
                                     String facing, float width, float height) {}

    /** 直链队列提交：一次可携带多条 URL */
    public record QueueAddPayload(List<String> urls) {}

    /** 队列按序号操作（jump/move/remove 共用），index 为 1-based */
    public record QueueIndexPayload(int index) {}

    /** 规则按名称操作（pull/delete/test 共用） */
    public record RuleNamePayload(String name) {}

    /** 规则列表请求：refresh=true 强制拉取云端目录（「刷新列表」按钮），false 读缓存（打开界面） */
    public record RuleListPayload(boolean refresh) {}

    // ---- S→C 响应 ----

    public record BangumiResultItem(String name, String date, String summary) {}

    public record RuleResultItem(String rule, String id, String name) {}

    /** 流式规则搜索单源增量（DATA_RULE_RESULTS_PARTIAL）：completed==total 表示全部源已完成 */
    public record RuleSearchPartialPayload(long searchId, String rule, List<RuleResultItem> items,
                                           int completedRules, int totalRules) {}

    /**
     * roads = 全部线路名列表；road = 本次集数列表对应的线路下标；
     * names/total = 该线路的集数名与总数
     */
    public record ChaptersPayload(List<String> roads, int road, List<String> names, int total) {}

    /**
     * 成功回包：key 为翻译键，title 为纯文本兜底；两者互斥使用。
     */
    public record PlayOkPayload(String title, String key, java.util.List<String> args) {
        public static PlayOkPayload literal(String title) {
            return new PlayOkPayload(title, null, java.util.List.of());
        }

        public static PlayOkPayload of(String key, String... args) {
            return new PlayOkPayload(null, key, java.util.List.of(args));
        }

        /** 渲染为可本地化组件 */
        public net.minecraft.network.chat.Component toComponent() {
            if (key != null && !key.isEmpty()) {
                var components = new net.minecraft.network.chat.Component[args == null ? 0 : args.size()];
                for (int i = 0; i < components.length; i++) {
                    components[i] = net.minecraft.network.chat.Component.literal(args.get(i));
                }
                return net.minecraft.network.chat.Component.translatable(key, (Object[]) components);
            }
            return net.minecraft.network.chat.Component.literal(title == null ? "" : title);
        }
    }

    /**
     * 错误回包：key 为翻译键（args 为填充参数），message 为纯文本兜底；
     * 两者互斥使用，反序列化旧格式时仅 message 有值。
     */
    public record ErrorPayload(String message, String key, List<String> args) {
        /** 纯文本错误（不经 i18n） */
        public static ErrorPayload literal(String message) {
            return new ErrorPayload(message, null, List.of());
        }

        /** 翻译键 + 填充参数 */
        public static ErrorPayload of(String key, String... args) {
            return new ErrorPayload(null, key, List.of(args));
        }

        /** 渲染为可本地化组件；旧格式仅 message 时按字面量显示 */
        public net.minecraft.network.chat.Component toComponent() {
            if (key != null && !key.isEmpty()) {
                var components = new net.minecraft.network.chat.Component[args == null ? 0 : args.size()];
                for (int i = 0; i < components.length; i++) {
                    components[i] = net.minecraft.network.chat.Component.literal(args.get(i));
                }
                return net.minecraft.network.chat.Component.translatable(key, (Object[]) components);
            }
            return net.minecraft.network.chat.Component.literal(message == null ? "" : message);
        }
    }

    // ---- 规则管理器 ----

    /** 远程目录+本地安装状态的合并条目；installed=false 时 installedVersion 为空串 */
    public record RuleListEntryPayload(String name, boolean installed, String installedVersion,
            String remoteVersion, boolean deprecated, String author) {}

    /** 单条规则连通性测试结果；ok=false 时 latency 为 -1 */
    public record RuleTestResultPayload(String name, long latency, boolean ok) {}

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
