package me.zuogeren.kazumiplayer;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志分类开关（common 配置 "log" 分组）。
 *
 * 每个分类对应 KazumiLog 中一个独立 logger（kazumiplayer.&lt;分类&gt;），
 * 关闭时该分类只输出 INFO 及以上日志；开启后额外输出 DEBUG 诊断日志。
 * 修改配置后通过 {@link #applyAll()} 即时生效，无需重启。
 */
public final class LogConfig {
    private LogConfig() {}

    /** 分类名 -> 配置值（按此顺序展示在配置界面） */
    private static final Map<String, ModConfigSpec.BooleanValue> SWITCHES = new LinkedHashMap<>();

    public static void define(ModConfigSpec.Builder builder) {
        builder.push("log");
        builder.comment(
                "日志分类 DEBUG 开关（默认关闭）",
                "开启后对应分类额外输出 DEBUG 级别诊断日志，修改后即时生效");

        SWITCHES.clear();
        add(builder, "general", "生命周期/通用日志");
        add(builder, "playback", "播放引擎日志");
        add(builder, "sniff", "视频嗅探日志");
        add(builder, "render", "渲染日志");
        add(builder, "screen", "屏幕方块日志");
        add(builder, "speaker", "音响日志");
        add(builder, "audio", "音频播放日志");
        add(builder, "network", "网络包日志");
        add(builder, "sync", "同步组日志");
        add(builder, "command", "命令日志");
        add(builder, "search", "搜索日志");
        add(builder, "rule", "规则引擎日志");
        add(builder, "http", "HTTP 请求日志");

        builder.pop();
    }

    private static void add(ModConfigSpec.Builder builder, String category, String comment) {
        SWITCHES.put(category, builder.comment(comment).define("debug" + Character.toUpperCase(category.charAt(0)) + category.substring(1), false));
    }

    /** 当前是否开启某分类的 DEBUG 日志（配置界面读取用） */
    public static boolean isDebugEnabled(String category) {
        ModConfigSpec.BooleanValue value = SWITCHES.get(category);
        return value != null && value.get();
    }

    /** 设置某分类开关并即时应用 */
    public static void setDebugEnabled(String category, boolean enabled) {
        ModConfigSpec.BooleanValue value = SWITCHES.get(category);
        if (value != null) {
            value.set(enabled);
        }
        apply(category, enabled);
    }

    /** 将当前配置值全部应用到 Log4j2（启动加载配置后、以及配置变更时调用） */
    public static void applyAll() {
        for (Map.Entry<String, ModConfigSpec.BooleanValue> e : SWITCHES.entrySet()) {
            apply(e.getKey(), e.getValue().get());
        }
    }

    private static void apply(String category, boolean debug) {
        String loggerName = "kazumiplayer" + (category.equals("general") ? "" : "." + category);
        try {
            // 通过反射调用 Log4j2 core API，避免编译期对 log4j-core 具体版本 API 的依赖。
            // 用 Logger.setLevel：直接设置该 logger 的独立级别，不会回退修改 root 配置。
            Class<?> contextClass = Class.forName("org.apache.logging.log4j.core.LoggerContext");
            Object context = contextClass.getMethod("getContext", boolean.class).invoke(null, false);
            Object logger = contextClass.getMethod("getLogger", String.class).invoke(context, loggerName);
            String levelName = debug ? "DEBUG" : "INFO";
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object level = levelClass.getField(levelName).get(null);
            logger.getClass().getMethod("setLevel", levelClass).invoke(logger, level);
        } catch (Throwable t) {
            // 日志框架异常不应影响游戏运行
            System.err.println("[KazumiPlayer] Failed to apply log level for " + loggerName + ": " + t);
        }
    }
}
