package fun.sakuraspark.sakuraupdater.gui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

public class MarkdownBox extends AbstractScrollWidget {
    private String markdownText;
    private List<String> lines;
    private boolean isScrollbarVisible=true;
    private boolean isBackgroundVisible=true;
    private int color = 0xFFFFFF;

    public MarkdownBox(int x, int y, int width, int height, String markdownText) {
        super(x, y, width, height, null);
        this.markdownText = markdownText;
        this.lines = parseMarkdown(markdownText);
    }

    // 简单的Markdown解析，仅支持# 标题、**加粗**、*斜体*和普通文本
    private List<String> parseMarkdown(String text) {
        List<String> result = new ArrayList<>();
        String[] rawLines = text.split("\n");
        Font font = Minecraft.getInstance().font;
        for (String line : rawLines) {
            // 这里只做简单处理，复杂的可以用第三方库
            if (line.startsWith("# ")) {
                line = "§l" + line.substring(2); // §l为Minecraft粗体
            } else if (line.startsWith("## ")) {
                line = "§n" + line.substring(3); // §n为Minecraft下划线
            } else {
                // 替换**加粗**
                line = line.replace("**", "§l").replace("*", "§o");
            }
            for (int i = 0; i < line.length(); ) {
                int nextBreak = font.plainSubstrByWidth(line.substring(i), this.getWidth() - 8).length();
                if (nextBreak == 0) break;
                String subLine = line.substring(i, i + nextBreak);
                result.add(subLine);
                i += nextBreak;
            }
        }
        return result;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE,Component.literal("update log"));
    }

    public void setColor(int color){
        this.color = color;
    }

    public void setBackgroundVisible(boolean visible) {
        this.isBackgroundVisible = visible;
    }

    @Override 
    protected void renderBackground(GuiGraphics guiGraphics) {
        if (this.isBackgroundVisible) {
            this.renderBorder(guiGraphics, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        }
    }

    @Override
    protected int getInnerHeight() {
        Font font = Minecraft.getInstance().font;
        return lines.size() * (font.lineHeight + 2);
    }

    @Override
    protected double scrollRate() {
        return 10.0; // 每次滚动10像素
    }

    public void setScrollbarVisible(boolean visible) {
        this.isScrollbarVisible = visible;
    }

    @Override 
    protected boolean scrollbarVisible() {
        if (!this.isScrollbarVisible) {
            return false;
        }
        return this.getInnerHeight() > this.getHeight();
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int yOffset = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            guiGraphics.drawString(font, line, this.getX() + 4, this.getY() + 4 + yOffset, color, false);
            yOffset += font.lineHeight + 2;
        }
    }
}
