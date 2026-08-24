package me.zuogeren.kazumiplayer.client.gui;

import me.zuogeren.kazumiplayer.client.gui.GuiClientState.Listener;
import me.zuogeren.kazumiplayer.network.gui.GuiPayloads;
import me.zuogeren.kazumiplayer.network.gui.GuiProtocol;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕设置独立界面：调整朝向/宽高/XYZ 偏移，即时生效。
 * 背景完全透明（世界清晰可见），便于对照世界里屏幕的实际位置与大小；
 * 「完成」或 Esc 返回播放器主界面（KazumiPlayerScreen）。
 */
public class ScreenPropsScreen extends Screen implements Listener {

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 216;
    private static final String[] FACING_CYCLE = {"north", "east", "south", "west"};

    private final BlockPos screenPos;
    /** 面板当前显示/提交的属性（以服务端回显的权威值为准） */
    private GuiPayloads.ScreenPropsPayload props =
        new GuiPayloads.ScreenPropsPayload(0, 0, 0, "north", 3.0f, 2.0f);

    private EditBox widthBox;
    private EditBox heightBox;
    private EditBox offXBox;
    private EditBox offYBox;
    private EditBox offZBox;
    private Button frameToggle;

    private static String frameLabel() {
        return frameLabel(me.zuogeren.kazumiplayer.screen.VideoScreenRenderer.isShowFrameWhilePlaying());
    }

    private static String frameLabel(boolean on) {
        return on ? Component.translatable("kazumiplayer.gui.props.frame_on").getString() : Component.translatable("kazumiplayer.gui.props.frame_off").getString();
    }

    public ScreenPropsScreen(BlockPos screenPos) {
        super(Component.translatable("kazumiplayer.gui.props.title"));
        this.screenPos = screenPos;
        loadFromScreen();
    }

    private VideoScreenBlockEntity boundScreen() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(screenPos) instanceof VideoScreenBlockEntity s ? s : null;
    }

    /** 打开界面：以绑定屏幕 BE 的当前属性为初始值 */
    private void loadFromScreen() {
        var s = boundScreen();
        if (s != null) {
            this.props = new GuiPayloads.ScreenPropsPayload(s.getOffsetX(), s.getOffsetY(), s.getOffsetZ(),
                s.getFacing().getName(), s.getScreenWidth(), s.getScreenHeight());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 不暂停单人世界；调偏移时实时观察方块移动
    }

    /** 背景置空：屏蔽默认菜单暗纹与模糊，保持世界清晰可见（所见即所得） */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    }

    @Override
    public void init() {
        int pw = PANEL_W;
        int px = (this.width - pw) / 2;
        int py = Math.max(16, (this.height - PANEL_H) / 2);
        int editX = px + 56;
        int editW = 56;
        int minusX = px + 122;
        int plusX = px + 146;

        addPropButton("◀", px + 66, py + 26, () -> cycleFacing(-1));
        addPropButton("▶", px + pw - 84, py + 26, () -> cycleFacing(1));

        widthBox = propEdit(editX, py + 54, editW, v -> setWidth(v));
        heightBox = propEdit(editX, py + 76, editW, v -> setHeight(v));
        offXBox = propEdit(editX, py + 102, editW, v -> setOffsetAxis(0, v));
        offYBox = propEdit(editX, py + 124, editW, v -> setOffsetAxis(1, v));
        offZBox = propEdit(editX, py + 146, editW, v -> setOffsetAxis(2, v));

        addPropButton("-", minusX, py + 54, () -> setWidth(props.width() - 0.5f));
        addPropButton("+", plusX, py + 54, () -> setWidth(props.width() + 0.5f));
        addPropButton("-", minusX, py + 76, () -> setHeight(props.height() - 0.5f));
        addPropButton("+", plusX, py + 76, () -> setHeight(props.height() + 0.5f));
        addPropButton("-", minusX, py + 102, () -> setOffsetAxis(0, props.offsetX() - 0.25f));
        addPropButton("+", plusX, py + 102, () -> setOffsetAxis(0, props.offsetX() + 0.25f));
        addPropButton("-", minusX, py + 124, () -> setOffsetAxis(1, props.offsetY() - 0.25f));
        addPropButton("+", plusX, py + 124, () -> setOffsetAxis(1, props.offsetY() + 0.25f));
        addPropButton("-", minusX, py + 146, () -> setOffsetAxis(2, props.offsetZ() - 0.25f));
        addPropButton("+", plusX, py + 146, () -> setOffsetAxis(2, props.offsetZ() + 0.25f));

        addWideButton(Component.translatable("kazumiplayer.gui.props.btn_reset").getString(), px + 12, py + PANEL_H - 26, () -> {
            setOffsetAxis(0, 0);
            setOffsetAxis(1, 0);
            setOffsetAxis(2, 0);
        });
        addWideButton(Component.translatable("kazumiplayer.gui.main.btn_close").getString(), px + pw - 74, py + PANEL_H - 26, this::onClose);

        // 播放中叠加屏幕面边框的开关（调整屏幕时标记设置范围，CONTAIN 缩放对照尤其实用）
        this.frameToggle = Button.builder(Component.literal(frameLabel()), btn -> {
                boolean on = me.zuogeren.kazumiplayer.screen.VideoScreenRenderer.toggleShowFrameWhilePlaying();
                btn.setMessage(Component.literal(frameLabel(on)));
            }).bounds(px + 12, py + 168, pw - 24, 16).build();
        this.addRenderableWidget(this.frameToggle);

        syncEditors();
        GuiClientState.setListener(this);
    }

    @Override
    public void removed() {
        GuiClientState.removeListenerIfOwner(this);
        super.removed();
    }

    @Override
    public void onGuiData(String dataType, String json) {
        if (GuiProtocol.DATA_SCREEN_PROPS.equals(dataType)) {
            // 服务端 clamp 后的权威值：覆盖本地并同步输入框（越界输入自动纠正）
            var p = GuiPayloads.fromJson(json, GuiPayloads.ScreenPropsPayload.class);
            if (p != null) {
                this.props = p;
                syncEditors();
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int pw = PANEL_W;
        int px = (this.width - pw) / 2;
        int py = Math.max(16, (this.height - PANEL_H) / 2);

        // 半透明面板底：世界透出，方便对照实际屏幕位置
        graphics.fill(px, py, px + pw, py + PANEL_H, 0xD0101018);
        graphics.text(this.font, Component.translatable("kazumiplayer.gui.props.title")
            .withStyle(ChatFormatting.WHITE), px + 12, py + 8, -1);

        // 行标签（输入框/按钮之间的文字层）
        propLabel(graphics, Component.translatable("kazumiplayer.gui.props.label_facing").getString(), px + 12, py + 31);
        propLabel(graphics, Component.translatable("kazumiplayer.gui.props.label_width").getString(), px + 12, py + 59);
        propLabel(graphics, Component.translatable("kazumiplayer.gui.props.label_height").getString(), px + 12, py + 81);
        propLabel(graphics, Component.translatable("kazumiplayer.gui.props.label_off_x").getString(), px + 12, py + 107);
        propLabel(graphics, Component.translatable("kazumiplayer.gui.props.label_off_y").getString(), px + 12, py + 129);
        propLabel(graphics, Component.translatable("kazumiplayer.gui.props.label_off_z").getString(), px + 12, py + 151);

        // 朝向当前值（两个循环按钮之间）
        String f = facingCn(props.facing());
        graphics.text(this.font, Component.literal(f).withStyle(ChatFormatting.YELLOW),
            px + 100 - this.font.width(f) / 2, py + 31, -1);

        // 输入框/按钮（控件层）
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    // ---- 操作 ----

    private void cycleFacing(int dir) {
        int cur = 0;
        for (int i = 0; i < FACING_CYCLE.length; i++) {
            if (FACING_CYCLE[i].equals(props.facing())) { cur = i; break; }
        }
        String next = FACING_CYCLE[Math.floorMod(cur + dir, FACING_CYCLE.length)];
        props = new GuiPayloads.ScreenPropsPayload(props.offsetX(), props.offsetY(), props.offsetZ(),
            next, props.width(), props.height());
        sendProps();
    }

    private void setWidth(float v) {
        v = clampF(v, 0.5f, 128.0f);
        if (v == props.width()) return;
        props = new GuiPayloads.ScreenPropsPayload(props.offsetX(), props.offsetY(), props.offsetZ(),
            props.facing(), v, props.height());
        sendProps();
        syncEditors();
    }

    private void setHeight(float v) {
        v = clampF(v, 0.5f, 128.0f);
        if (v == props.height()) return;
        props = new GuiPayloads.ScreenPropsPayload(props.offsetX(), props.offsetY(), props.offsetZ(),
            props.facing(), props.width(), v);
        sendProps();
        syncEditors();
    }

    /** axis: 0=X, 1=Y, 2=Z */
    private void setOffsetAxis(int axis, float v) {
        v = clampF(v, -32.0f, 32.0f);
        float ox = props.offsetX(), oy = props.offsetY(), oz = props.offsetZ();
        if (axis == 0) { if (v == ox) return; ox = v; }
        else if (axis == 1) { if (v == oy) return; oy = v; }
        else { if (v == oz) return; oz = v; }
        props = new GuiPayloads.ScreenPropsPayload(ox, oy, oz, props.facing(), props.width(), props.height());
        sendProps();
        syncEditors();
    }

    private void sendProps() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundCustomPayloadPacket(
                new me.zuogeren.kazumiplayer.network.packet.GuiActionPacket(
                    screenPos, GuiProtocol.ACTION_SCREEN_PROPS, GuiPayloads.toJson(props))));
        }
    }

    /** 用权威值刷新各输入框；框内已等价（含正在输入的中间态）时不动，避免打断手动输入 */
    private void syncEditors() {
        syncEdit(widthBox, props.width());
        syncEdit(heightBox, props.height());
        syncEdit(offXBox, props.offsetX());
        syncEdit(offYBox, props.offsetY());
        syncEdit(offZBox, props.offsetZ());
    }

    private static void syncEdit(EditBox box, float v) {
        if (box == null) return;
        float cur = Float.NaN;
        try { cur = Float.parseFloat(box.getValue().trim()); } catch (NumberFormatException ignored) {}
        if (Float.isNaN(cur) || Math.abs(cur - v) > 0.001f) box.setValue(trim1(v));
    }

    @Override
    public void onClose() {
        // 返回播放器主界面（搜索状态为 static，重开后保留）
        Minecraft.getInstance().setScreen(new KazumiPlayerScreen(screenPos));
    }

    // ---- 小工具 ----

    /** 面板数值行的行首标签（渲染在输入框左侧） */
    private void propLabel(GuiGraphicsExtractor graphics, String label, int ppx, int y) {
        graphics.text(this.font, Component.literal(label).withStyle(ChatFormatting.GRAY), ppx + 12, y + 4, -1);
    }

    private void addPropButton(String label, int x, int y, Runnable action) {
        var b = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, 18, 16).build();
        this.addRenderableWidget(b);
    }

    private void addWideButton(String label, int x, int y, Runnable action) {
        var b = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, 62, 16).build();
        this.addRenderableWidget(b);
    }

    /** 数值输入框：输入合法即提交（提交内部有等值早退，回显不会打断手动输入） */
    private EditBox propEdit(int x, int y, int width, java.util.function.Consumer<Float> commit) {
        var e = new EditBox(this.font, x, y, width, 16, Component.literal(""));
        e.setMaxLength(9);
        e.setResponder(text -> {
            var t = text.trim();
            if (t.isEmpty()) return;
            try {
                float v = Float.parseFloat(t);
                if (!Float.isNaN(v) && !Float.isInfinite(v)) commit.accept(v);
            } catch (NumberFormatException ignored) {}
        });
        this.addRenderableWidget(e);
        return e;
    }

    private static String facingCn(String facing) {
        return switch (facing) {
            case "north" -> Component.translatable("kazumiplayer.gui.props.dir_north").getString();
            case "east" -> Component.translatable("kazumiplayer.gui.props.dir_east").getString();
            case "south" -> Component.translatable("kazumiplayer.gui.props.dir_south").getString();
            case "west" -> Component.translatable("kazumiplayer.gui.props.dir_west").getString();
            default -> facing;
        };
    }

    /** 保留至多两位小数并去尾零（3.00→3、0.25→0.25）；Locale.ROOT 保证小数点为 "."，逗号 locale 下 Float.parseFloat 才能回读 */
    private static String trim1(float v) {
        var s = String.format(java.util.Locale.ROOT, "%.2f", v);
        if (s.endsWith("00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
