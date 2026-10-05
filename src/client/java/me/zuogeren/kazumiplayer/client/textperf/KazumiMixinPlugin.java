package me.zuogeren.kazumiplayer.client.textperf;

import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 移植项（ImmediatelyFast 的文本渲染优化）的加载期闸门。
 *
 * <p>已安装 ImmediatelyFast 时**完全不注册本模组的这三个 mixin**：两边注入同一个调用点
 * （文本 RenderType 的 sortOnUpload、字形缓冲查询、字形图集常量）时，Mixin 只允许一个
 * {@code @Redirect} 生效，另一个会被跳过——若恰好是本模组的生效而对方被跳过，反而可能让
 * 对方的优化失效；图集常量这类 {@code @ModifyConstant} 还会互相覆盖对方的配置值。
 * 因此检测到对方存在时直接让位。
 *
 * <p>同时处理应急开关 {@code -Dkazumiplayer.mixin.disableAll=true} 与
 * {@code -Dkazumiplayer.mixin.fontAtlasSize=256}（后者只关图集扩容一项）。
 */
public class KazumiMixinPlugin implements IMixinConfigPlugin {

    private boolean disabled;

    @Override
    public void onLoad(String mixinPackage) {
        disabled = KazumiTextPerf.disableAll() || immediatelyFastLoaded();
        if (disabled) {
            me.zuogeren.kazumiplayer.util.KazumiLog.render.info(
                "Text render mixins disabled ({})",
                KazumiTextPerf.disableAll() ? "kazumiplayer.mixin.disableAll" : "immediatelyfast present");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (disabled) return false;
        return !(KazumiTextPerf.atlasDisabled() && mixinClassName.endsWith("MixinFontTexture"));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean immediatelyFastLoaded() {
        try {
            return ModList.get().isLoaded("immediatelyfast");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
