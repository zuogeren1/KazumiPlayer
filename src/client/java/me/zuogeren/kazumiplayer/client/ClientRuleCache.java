package me.zuogeren.kazumiplayer.client;

import me.zuogeren.kazumiplayer.rule.Rule;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端规则缓存 (从 RuleSyncPacket 更新)
 */
public class ClientRuleCache {
    private static final Map<String, Rule> rules = new ConcurrentHashMap<>();

    /**
     * 从服务端同步的 JSON 更新规则列表
     */
    public static void updateFromJson(String rulesJson) {
        rules.clear();
        List<Rule> loaded = JsonUtil.GSON.fromJson(rulesJson,
                new TypeToken<List<Rule>>() {}.getType());
        if (loaded != null) {
            for (Rule rule : loaded) {
                rules.put(rule.getName(), rule);
            }
        }
    }

    public static Rule get(String name) {
        return rules.get(name);
    }

    public static List<String> listAll() {
        List<String> names = new java.util.ArrayList<>(rules.keySet());
        Collections.sort(names);
        return names;
    }

    public static int size() {
        return rules.size();
    }
}
