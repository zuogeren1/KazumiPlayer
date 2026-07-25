package me.zuogeren.kazumiplayer.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 简易 JSONPath 解析器，只支持 Kazumi 规则中使用的受限语法:
 * $, .key, [*], [N], ['key'], ["key"]
 * 不依赖外部库
 */
public class SimpleJsonPath {

    /**
     * 在 JSON 文档上求值 JSONPath 表达式
     */
    public static List<JsonElement> read(String json, String path) {
        JsonElement root = JsonParser.parseString(json);
        return evaluate(root, path);
    }

    /**
     * 在已解析的 JSON 元素上求值
     */
    public static List<JsonElement> read(JsonElement root, String path) {
        return evaluate(root, path);
    }

    /**
     * 读取单个值，没有则返回 null
     */
    public static String readFirst(String json, String path) {
        List<JsonElement> results = read(json, path);
        if (results.isEmpty()) return null;
        JsonElement e = results.get(0);
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }

    public static String readFirst(JsonElement root, String path) {
        List<JsonElement> results = evaluate(root, path);
        if (results.isEmpty()) return null;
        JsonElement e = results.get(0);
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }

    private static List<JsonElement> evaluate(JsonElement current, String path) {
        List<JsonElement> results = new ArrayList<>();
        if (path == null || path.isEmpty() || "$".equals(path)) {
            results.add(current);
            return results;
        }

        // 去掉开头的 $
        String expr = path.startsWith("$.") ? path.substring(2) : path;
        if (expr.startsWith("$[")) expr = expr.substring(1);

        evaluateSegment(current, expr, results);
        return results;
    }

    private static void evaluateSegment(JsonElement current, String remaining, List<JsonElement> results) {
        if (remaining.isEmpty()) {
            results.add(current);
            return;
        }

        // 解析下一个 segment
        String segment;
        String rest;

        if (remaining.startsWith("[")) {
            int end = remaining.indexOf(']');
            if (end < 0) { results.add(current); return; }
            segment = remaining.substring(0, end + 1);
            rest = remaining.substring(end + 1);
            if (rest.startsWith(".")) rest = rest.substring(1);
        } else if (remaining.startsWith(".")) {
            remaining = remaining.substring(1);
            int dot = remaining.indexOf('.');
            int bracket = remaining.indexOf('[');
            int end;
            if (dot < 0 && bracket < 0) end = remaining.length();
            else if (dot < 0) end = bracket;
            else if (bracket < 0) end = dot;
            else end = Math.min(dot, bracket);
            segment = remaining.substring(0, end);
            rest = remaining.substring(end);
            if (rest.startsWith(".")) rest = rest.substring(1);
        } else {
            // 键名
            int dot = remaining.indexOf('.');
            int bracket = remaining.indexOf('[');
            int end;
            if (dot < 0 && bracket < 0) end = remaining.length();
            else if (dot < 0) end = bracket;
            else if (bracket < 0) end = dot;
            else end = Math.min(dot, bracket);
            segment = remaining.substring(0, end);
            rest = remaining.substring(end);
            if (rest.startsWith(".")) rest = rest.substring(1);
        }

        // 应用 segment 到 current
        if (segment.startsWith("[") && segment.endsWith("]")) {
            String inner = segment.substring(1, segment.length() - 1);
            if ("*".equals(inner)) {
                // 通配符: 对数组每个元素继续求值
                if (current.isJsonArray()) {
                    JsonArray arr = current.getAsJsonArray();
                    for (JsonElement item : arr) {
                        evaluateSegment(item, rest, results);
                    }
                }
            } else {
                // 整数索引
                try {
                    int idx = Integer.parseInt(inner);
                    if (current.isJsonArray()) {
                        JsonArray arr = current.getAsJsonArray();
                        if (idx >= 0 && idx < arr.size()) {
                            evaluateSegment(arr.get(idx), rest, results);
                        }
                    }
                } catch (NumberFormatException e) {
                    results.add(current);
                }
            }
        } else {
            // 对象键名 (去掉可能存在的引号)
            String key = segment;
            if ((key.startsWith("'") && key.endsWith("'")) ||
                (key.startsWith("\"") && key.endsWith("\""))) {
                key = key.substring(1, key.length() - 1);
            }
            if (current.isJsonObject()) {
                JsonObject obj = current.getAsJsonObject();
                if (obj.has(key)) {
                    evaluateSegment(obj.get(key), rest, results);
                }
            }
        }
    }
}
