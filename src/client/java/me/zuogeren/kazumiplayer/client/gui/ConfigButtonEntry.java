package me.zuogeren.kazumiplayer.client.gui;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/**
 * Cloth Config 条目：左侧标签 + 右侧动作按钮（Cloth 无内置按钮条目）。
 * 复用原版 Button 作为子组件：坐标在渲染时按条目区域计算，事件由 {@link #children()} 暴露给 Cloth 转发。
 */
public class ConfigButtonEntry extends AbstractConfigListEntry<Boolean> {

    private final Button button;

    public ConfigButtonEntry(Component label, Component buttonText, Runnable action) {
        super(label, false);
        this.button = Button.builder(buttonText, b -> action.run()).bounds(0, 0, 150, 20).build();
    }

    /** 按钮实例：供调用方动态改写文案/可用态（如登录后把「打开二维码」换成「已登录」） */
    public Button button() {
        return button;
    }

    @Override
    public Optional<Boolean> getDefaultValue() {
        return Optional.empty();
    }

    /** 无状态条目：只承载动作，不参与配置读写 */
    @Override
    public Boolean getValue() {
        return false;
    }

    @Override
    public boolean isRequiresRestart() {
        return false;
    }

    @Override
    public void setRequiresRestart(boolean requiresRestart) {
    }

    @Override
    public int getItemHeight() {
        return 24;
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of(button);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of(button);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return button.mouseClicked(event, doubled);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x,
            int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
        Minecraft mc = Minecraft.getInstance();
        boolean enabled = this.isEnabled();
        graphics.text(mc.font, getFieldName(), x, y + (entryHeight - 8) / 2,
            enabled ? 0xFFFFFFFF : 0xFF808080);
        int width = Math.min(150, Math.max(60, entryWidth / 2 - 8));
        button.setX(x + entryWidth - width - 4);
        button.setY(y + Math.max(0, (entryHeight - 20) / 2));
        button.setWidth(width);
        button.active = enabled;
        button.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
