package me.zuogeren.kazumiplayer.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.rule.dto.Road;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Gson 实例配置
 */
public class JsonUtil {
    public static final Gson GSON = new GsonBuilder()
            .setLenient()
            .create();

    public static final Gson GSON_PRETTY = new GsonBuilder()
            .setLenient()
            .setPrettyPrinting()
            .create();

    /**
     * 解析 episodeData JSON 中的第一条 Road。数据为空或解析失败返回 null。
     * 封装了 {json → List&lt;Road&gt; → roads.get(0)} 的重复样板。
     */
    @Nullable
    public static Road parseFirstRoad(String json) {
        if (json == null || json.isEmpty()) return null;
        List<Road> roads = GSON.fromJson(json, new TypeToken<List<Road>>() {}.getType());
        if (roads == null || roads.isEmpty()) return null;
        return roads.get(0);
    }
}
