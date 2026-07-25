package me.zuogeren.kazumiplayer.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
}
