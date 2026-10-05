/*
 * Ported from ImmediatelyFast (LGPL-3.0) - https://github.com/RaphiMC/ImmediatelyFast
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * Source: common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/font_atlas_resizing/MixinFontTexture.java (26.1 分支)
 * 本文件按 GPL-3.0-only 随 KazumiPlayer 分发（LGPL-3.0 与 GPL-3.0 兼容）。
 */
package me.zuogeren.kazumiplayer.mixin;

import me.zuogeren.kazumiplayer.client.textperf.KazumiTextPerf;

import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 字形图集扩容：原版每张图集 256×256，遇到 CJK 弹幕（每帧大量互不相同的汉字）会很快填满并新开图集，
 * 而**不同图集纹理 = 不同 RenderType**：字形在图集之间来回切换时，{@code MultiBufferSource.BufferSource}
 * 会不断 {@code endBatch}（换缓冲、上传、必要时排序）再新建缓冲，批次被切碎成大量 draw call。
 * 把图集边长提到 1024（默认）后同样的字形集合能落在更少的图集里，切纹理次数大幅下降。
 *
 * <p>255/256 这类常量在 26.1.2 mojmap {@code net/minecraft/client/gui/font/FontTexture.java} 里同时用于
 * 纹理尺寸、分配器根节点与 UV 归一化（L19/L26/L28/L41-42/L48-51），故 int 与 float 两个常量都要改，
 * 且必须用同一个值——这里两个 handler 都读 {@link KazumiTextPerf#fontAtlasSize()}。
 *
 * <p>影响面：全局字形图集（含 ASCII 图集与各 unicode 图集），VRAM 占用按边长平方增长
 * （RED8：256²=64KB → 1024²=1MB；着色图集 RGBA8：256KB → 4MB）。回滚：{@code -Dkazumiplayer.mixin.fontAtlasSize=256}。
 *
 * <p>{@code require = 0}：与 ImmediatelyFast 等已做同一注入的模组共存时不抢注入点、不硬失败。
 */
@Mixin(FontTexture.class)
public abstract class MixinFontTexture {

    @ModifyConstant(method = "*", constant = @Constant(intValue = 256), require = 0)
    private int kazumiplayer$fontAtlasSizeInt(int original) {
        return KazumiTextPerf.fontAtlasSize();
    }

    @ModifyConstant(method = "*", constant = @Constant(floatValue = 256.0F), require = 0)
    private float kazumiplayer$fontAtlasSizeFloat(float original) {
        return KazumiTextPerf.fontAtlasSize();
    }
}
