package me.zuogeren.kazumiplayer.mixin;

import me.zuogeren.kazumiplayer.util.KazumiLog;

/**
 * 文本渲染优化开关（ImmediatelyFast 移植项的运行期闸门）。
 *
 * <p>移植项都是**全局生效**的渲染管线改动（文本 RenderType 的排序、字形缓冲复用、字形图集边长），
 * 不做成 ClientConfig 选项是为了不改动配置对外结构；需要回滚时用系统属性（JVM 参数）：
 *
 * <ul>
 *   <li>{@code -Dkazumiplayer.mixin.skipTextSorting=false}：恢复文本 RenderType 的逐次半透明排序；</li>
 *   <li>{@code -Dkazumiplayer.mixin.reuseGlyphBuffer=false}：恢复逐字形 {@code MultiBufferSource#getBuffer} 查询；</li>
 *   <li>{@code -Dkazumiplayer.mixin.fontAtlasSize=256}：恢复原版字形图集边长（256）；</li>
 *   <li>{@code -Dkazumiplayer.mixin.disableAll=true}：一次关掉全部三项（应急回滚）。</li>
 * </ul>
 *
 * <p>更彻底的回滚：从 {@code kazumiplayer.client.mixins.json} 的 {@code client} 数组里删掉对应 mixin 类，
 * 或从 {@code neoforge.mods.toml} 里删掉 {@code [[mixins]]} 段（后者会让三项全部失效）。
 *
 * <p>读取只在类初始化时做一次（属性是启动参数，运行期不改）。
 */
public final class KazumiTextPerf {

    /** 跳过文本 RenderType 的 sortOnUpload（半透明排序） */
    private static final boolean SKIP_TEXT_SORTING =
        boolProperty("kazumiplayer.mixin.skipTextSorting", true);
    /** 字形缓冲复用（同一 RenderType 连续字形不再查 BufferSource 映射） */
    private static final boolean REUSE_GLYPH_BUFFER =
        boolProperty("kazumiplayer.mixin.reuseGlyphBuffer", true);
    /** 字形图集边长（原版 256；越大则 CJK 字形越少跨图集，纹理切换与批次刷新越少） */
    private static final int FONT_ATLAS_SIZE = atlasSize();

    /** 项目默认字形图集边长：与 ImmediatelyFast 的 font_atlas_size 默认值一致（1024） */
    private static final int DEFAULT_FONT_ATLAS_SIZE = 1024;
    /** 原版字形图集边长 */
    private static final int VANILLA_FONT_ATLAS_SIZE = 256;
    private static final boolean DISABLE_ALL = boolProperty("kazumiplayer.mixin.disableAll", false);

    static {
        KazumiLog.render.info(
            "Text render mixins: skip-text-sorting={}, reuse-glyph-buffer={}, font-atlas-size={}"
                + " (rollback: -Dkazumiplayer.mixin.* )",
            skipTextSorting(), reuseGlyphBuffer(), fontAtlasSize());
    }

    /** @return true=文本 RenderType 不做 sortOnUpload（半透明排序） */
    public static boolean skipTextSorting() {
        return !DISABLE_ALL && SKIP_TEXT_SORTING;
    }

    /** @return true=同一 RenderType 的连续字形复用上次取到的 VertexConsumer */
    public static boolean reuseGlyphBuffer() {
        return !DISABLE_ALL && REUSE_GLYPH_BUFFER;
    }

    /** @return 字形图集边长（{@link #VANILLA_FONT_ATLAS_SIZE} 表示原版行为；必须是 2 的幂） */
    public static int fontAtlasSize() {
        return DISABLE_ALL ? VANILLA_FONT_ATLAS_SIZE : FONT_ATLAS_SIZE;
    }

    private static boolean boolProperty(String key, boolean fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** 图集边长：缺省 1024；非法值（非 2 的幂或越界）回落原版 256 并记一条日志 */
    private static int atlasSize() {
        String value = System.getProperty("kazumiplayer.mixin.fontAtlasSize");
        if (value == null) return DEFAULT_FONT_ATLAS_SIZE;
        try {
            int size = Integer.parseInt(value.trim());
            if (size != VANILLA_FONT_ATLAS_SIZE
                    && (size < 512 || size > 4096 || (size & (size - 1)) != 0)) {
                KazumiLog.render.warn(
                    "Invalid kazumiplayer.mixin.fontAtlasSize={} (expect 256 or a power of two in"
                        + " [512, 4096]); falling back to 256", value);
                return VANILLA_FONT_ATLAS_SIZE;
            }
            return size;
        } catch (NumberFormatException e) {
            KazumiLog.render.warn("Unparsable kazumiplayer.mixin.fontAtlasSize={}; using 256", value);
            return VANILLA_FONT_ATLAS_SIZE;
        }
    }

    private KazumiTextPerf() {}
}
