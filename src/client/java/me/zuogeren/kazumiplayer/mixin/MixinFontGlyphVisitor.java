/*
 * Ported from ImmediatelyFast (LGPL-3.0) - https://github.com/RaphiMC/ImmediatelyFast
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * Source: common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/fast_text_lookup/MixinFont_GlyphVisitor.java (26.1 分支)
 * 本文件按 GPL-3.0-only 随 KazumiPlayer 分发（LGPL-3.0 与 GPL-3.0 兼容）。
 */
package me.zuogeren.kazumiplayer.mixin;

import me.zuogeren.kazumiplayer.client.textperf.KazumiTextPerf;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 字形缓冲复用：{@code Font$GlyphVisitor$1#render} 对**每个字形**都调一次
 * {@code MultiBufferSource#getBuffer(renderType)}（内部是 {@code HashMap#get} + 相等性判断）。
 * 同一串文本里相邻字形绝大多数落在同一图集纹理（同一 RenderType）上，故缓存上一次的
 * (RenderType, VertexConsumer) 对即可跳过绝大多数映射查找——弹幕一帧上万字形时这是实打实的开销。
 *
 * <p>目标 {@code Font$GlyphVisitor$1} 是 {@code Font.GlyphVisitor#forMultiBufferSource} 的匿名实现
 * （26.1.2 mojmap {@code net/minecraft/client/gui/Font.java} L239-L259，{@code private void render(TextRenderable)}
 * 在 L254-L257 调 {@code getBuffer}），实际字节码用 javap 核对过类名与方法名。
 * 该匿名实例是「一次文本准备对应一个」的短生命周期对象，缓存字段随实例回收。
 *
 * <p>{@code require = 0}：与 ImmediatelyFast 等已做同一注入的模组共存时不抢注入点、不硬失败。
 */
@Mixin(targets = "net.minecraft.client.gui.Font$GlyphVisitor$1")
public abstract class MixinFontGlyphVisitor {

    @Unique
    private RenderType kazumiplayer$lastRenderType;

    @Unique
    private VertexConsumer kazumiplayer$lastVertexConsumer;

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
        ),
        require = 0
    )
    private VertexConsumer kazumiplayer$reuseGlyphBuffer(MultiBufferSource source, RenderType renderType) {
        if (!KazumiTextPerf.reuseGlyphBuffer()) return source.getBuffer(renderType);
        if (this.kazumiplayer$lastRenderType == renderType) return this.kazumiplayer$lastVertexConsumer;
        this.kazumiplayer$lastRenderType = renderType;
        this.kazumiplayer$lastVertexConsumer = source.getBuffer(renderType);
        return this.kazumiplayer$lastVertexConsumer;
    }
}
