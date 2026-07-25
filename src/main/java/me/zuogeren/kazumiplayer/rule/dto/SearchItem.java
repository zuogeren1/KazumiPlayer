package me.zuogeren.kazumiplayer.rule.dto;

import com.google.gson.annotations.SerializedName;

/**
 * 搜索结果条目: 番剧名称 + 详情页 URL
 */
public record SearchItem(
        @SerializedName("name") String name,
        @SerializedName("src") String src) {
}
