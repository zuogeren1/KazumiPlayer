package me.zuogeren.kazumiplayer.rule.dto;

import java.util.List;

/**
 * 播放线路: 名称 + 剧集 URL 列表 + 剧集名称列表
 * data[i] 和 identifier[i] 为平行数组，对应第 i 集
 */
public record Road(
        String name,
        List<String> data,
        List<String> identifier) {
}
