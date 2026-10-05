/*
 * Ported from ImmediatelyFast (LGPL-3.0) - https://github.com/RaphiMC/ImmediatelyFast
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * Source: common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/skip_text_translucency_sorting/MixinRenderTypes.java (26.1 分支)
 * 本文件按 GPL-3.0-only 随 KazumiPlayer 分发（LGPL-3.0 与 GPL-3.0 兼容）。
 */
package me.zuogeren.kazumiplayer.mixin;

import me.zuogeren.kazumiplayer.client.textperf.KazumiTextPerf;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 去掉文本 RenderType 的 {@code sortOnUpload()}——弹幕是「大量互不遮挡的小文本四边形」，
 * 逐次上传时的半透明排序（{@code MeshData#sortQuads}）纯属浪费；文本批次内部的四边形成对出现
 * 且基本共面，排序不改变可见结果。
 *
 * <p>目标 lambda 名按 26.1.2 的实际字节码核对（{@code build/moddev/artifacts/minecraft-patched-26.1.2.86-merged.jar}
 * 上 {@code javap -p -c net.minecraft.client.renderer.rendertype.RenderTypes}）：
 * <ul>
 *   <li>{@code lambda$static$22} → {@code TEXT_POLYGON_OFFSET}（弹幕世界层用的就是它）；</li>
 *   <li>{@code lambda$static$23} → {@code TEXT_INTENSITY_POLYGON_OFFSET}；</li>
 *   <li>{@code lambda$static$25} → {@code TEXT_INTENSITY_SEE_THROUGH}。</li>
 * </ul>
 * 26.1.2 的 {@code RenderTypes} 里带 {@code sortOnUpload()} 的文本类型恰好是这三个，
 * 与 ImmediatelyFast 26.1 分支的注释一致（{@code TEXT}/{@code TEXT_INTENSITY}/{@code TEXT_SEE_THROUGH} 本来就不排序）。
 *
 * <p>{@code require = 0}：与 ImmediatelyFast 等已做同一注入的模组共存时不抢注入点、不硬失败
 * （拿不到注入点就退回原版行为，只是少了这项优化）。
 */
@Mixin(RenderTypes.class)
public abstract class MixinRenderTypes {

    @Redirect(
        method = {
            "lambda$static$22",
            "lambda$static$23",
            "lambda$static$25"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder;sortOnUpload()Lnet/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder;"
        ),
        require = 0
    )
    private static RenderSetup.RenderSetupBuilder kazumiplayer$skipTextTranslucencySorting(
            RenderSetup.RenderSetupBuilder builder) {
        return KazumiTextPerf.skipTextSorting() ? builder : builder.sortOnUpload();
    }
}
