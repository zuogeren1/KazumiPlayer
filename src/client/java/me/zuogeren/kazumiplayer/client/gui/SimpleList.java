package me.zuogeren.kazumiplayer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * GUI 通用行列表（AbstractSelectionList 最小封装）。
 * 支持两种行：单文本行（搜索结果）与横向网格行（选集按钮阵列）。
 */
public class SimpleList extends AbstractSelectionList<SimpleList.Row> {

    /** 网格行中的一个可点单元 */
    public record Cell(String text, Runnable onClick) {}

    public SimpleList(Minecraft mc, int width, int height, int y, int rowHeight) {
        super(mc, width, height, y, rowHeight);
    }

    public void addRow(Component text, int color, Runnable onClick) {
        this.addEntry(new Row(List.of(new Cell(text.getString(), onClick)), color));
    }

    /** 横向网格行：cells 均分行宽，逐格绘制与点击 */
    public void addGridRow(List<Cell> cells) {
        this.addEntry(new Row(cells, -1));
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        // 简单文本行无需旁白详情
    }

    /** 父类默认框整行；改为委托给 Row 按格绘制（网格行只框选中的格） */
    @Override
    protected void extractSelection(GuiGraphicsExtractor graphics, Row entry, int outlineColor) {
        entry.extractCellSelection(graphics, outlineColor);
    }

    public static class Row extends AbstractSelectionList.Entry<Row> {
        private final List<Cell> cells;
        private final int color;
        private int selectedCell = -1;

        public Row(List<Cell> cells, int color) {
            this.cells = cells;
            this.color = color;
        }

        private int cellWidth() {
            return Math.max(1, (this.getWidth() - 8) / Math.max(1, this.cells.size()));
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int textY = this.getY() + (this.getHeight() - mc.font.lineHeight) / 2;
            int cellW = this.cellWidth();
            for (int i = 0; i < this.cells.size(); i++) {
                int cx = this.getX() + 4 + i * cellW;
                String s = mc.font.plainSubstrByWidth(this.cells.get(i).text(), cellW - 6);
                graphics.text(mc.font, Component.literal(s), cx, textY, this.color);
            }
        }

        /** 只框住选中的格（左右各留 2px 间隙），而非整行 */
        private void extractCellSelection(GuiGraphicsExtractor graphics, int outlineColor) {
            if (this.selectedCell < 0 || this.selectedCell >= this.cells.size()) return;
            int cellW = this.cellWidth();
            int x0 = this.getX() + 4 + this.selectedCell * cellW;
            int y0 = this.getY();
            int x1 = x0 + cellW - 2;
            int y1 = y0 + this.getHeight();
            graphics.fill(x0, y0, x1, y1, outlineColor);
            graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, -16777216);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            int idx = (int) ((event.x() - this.getX() - 4) / this.cellWidth());
            if (idx < 0 || idx >= this.cells.size()) return false;
            Runnable onClick = this.cells.get(idx).onClick();
            if (onClick != null) {
                this.selectedCell = idx;
                onClick.run();
                return true;
            }
            return false;
        }
    }
}
