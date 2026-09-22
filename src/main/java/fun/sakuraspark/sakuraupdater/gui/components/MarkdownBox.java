package fun.sakuraspark.sakuraupdater.gui.components;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownBox extends AbstractScrollWidget {
    // 标题允许最多三个前导空格；# 后须为空白或行尾，避免将 #标签 识别为标题。
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|$)");
    // headingLevel 为 0 表示正文，1～6 表示对应级别的标题。
    private record MarkdownLine(Component content, int headingLevel) {}
    // 折行后的绘制数据：y 相对内容起点，height 包含行间距，二者均为缩放后的像素值。
    private record RenderLine(FormattedCharSequence content, float scale, int y, int height) {}

    private final List<MarkdownLine> lines;
    private final List<RenderLine> renderedLines = new ArrayList<>();
    private int layoutWidth = -1;
    private int contentHeight;
    private boolean isScrollbarVisible = true;
    private boolean isBackgroundVisible = true;
    private int color = 0xFFFFFF;

    public MarkdownBox(int x, int y, int width, int height, String markdownText) {
        super(x, y, width, height, Component.literal("update log"));
        this.lines = parseMarkdown(markdownText);
    }

    // 源文本每行对应一项，保留空行；按宽度自动折行由布局阶段处理。
    private List<MarkdownLine> parseMarkdown(String text) {
        List<MarkdownLine> result = new ArrayList<>();
        // \R 兼容不同平台的换行符，-1 保留末尾空行。
        for (String rawLine : text.split("\\R", -1)) {
            Matcher heading = HEADING.matcher(rawLine);
            int level = 0;
            if (heading.matches()) {
                level = heading.group(1).length();
                rawLine = heading.group(2) == null ? "" : heading.group(2);
                // 去掉标题末尾可选的闭合标记，例如「## 标题 ##」末尾的 ##。
                rawLine = rawLine.replaceFirst("[ \\t]+#+[ \\t]*$", "");
            }
            Style style = level == 0 ? Style.EMPTY : Style.EMPTY.withBold(true);
            result.add(new MarkdownLine(parseInline(rawLine, style, 0), level));
        }
        return result;
    }

    // 简单的行内 Markdown 解析：支持粗体、斜体、删除线、代码、转义和链接。
    private Component parseInline(String text, Style style, int depth) {
        // 限制嵌套深度，过深的剩余内容按原文显示，避免递归耗尽调用栈。
        if (depth >= 32) return Component.literal(text).setStyle(style);
        MutableComponent result = Component.empty();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && "\\`*_{}[]<>()#+-.!~".indexOf(text.charAt(i + 1)) >= 0) {
                plain.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            // 链接文字可递归解析粗体等样式，但已有链接样式时不再解析嵌套链接。
            if (c == '[' && style.getClickEvent() == null) {
                int labelEnd = findClosing(text, i, '[', ']');
                if (labelEnd > i && labelEnd + 1 < text.length() && text.charAt(labelEnd + 1) == '(') {
                    int urlEnd = findClosing(text, labelEnd + 1, '(', ')');
                    if (urlEnd > labelEnd) {
                        String url = text.substring(labelEnd + 2, urlEnd).trim();
                        if (isWebUrl(url)) {
                            flush(result, plain, style);
                            Style linkStyle = style.withColor(ChatFormatting.BLUE).withUnderlined(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                            result.append(parseInline(text.substring(i + 1, labelEnd), linkStyle, depth + 1));
                            i = urlEnd + 1;
                            continue;
                        }
                    }
                }
            }
            String marker = null;
            // 优先匹配较长的星号标记，避免将 *** 或 ** 拆成多个 *。
            for (String candidate : new String[]{"`", "***", "**", "*", "~~"}) {
                if (text.startsWith(candidate, i)) {
                    marker = candidate;
                    break;
                }
            }
            if (marker != null) {
                int end = findMarker(text, marker, i + marker.length());
                if (end > i + marker.length()) {
                    flush(result, plain, style);
                    String inner = text.substring(i + marker.length(), end);
                    Style innerStyle = switch (marker) {
                        case "***" -> style.withBold(true).withItalic(true);
                        case "**" -> style.withBold(true);
                        case "*" -> style.withItalic(true);
                        case "~~" -> style.withStrikethrough(true);
                        default -> style.withColor(ChatFormatting.GRAY);
                    };
                    // 代码片段保留字面内容；其他片段递归解析，并继承当前样式。
                    // innerStyle 只作用于当前片段，结束后继续使用外层 style，防止样式泄漏。
                    result.append(marker.equals("`") ? Component.literal(inner).setStyle(innerStyle)
                            : parseInline(inner, innerStyle, depth + 1));
                    i = end + marker.length();
                    continue;
                }
                // 未配对的标记整体保留为原文，避免将其中一部分误解析为其他标记。
                plain.append(marker);
                i += marker.length();
                continue;
            }
            plain.append(c);
            i++;
        }
        flush(result, plain, style);
        return result;
    }

    private static void flush(MutableComponent target, StringBuilder plain, Style style) {
        // 合并连续普通字符，在切换样式前一次性追加，避免为每个字符创建 Component。
        if (!plain.isEmpty()) {
            target.append(Component.literal(plain.toString()).setStyle(style));
            plain.setLength(0);
        }
    }

    private static int findClosing(String text, int start, char open, char close) {
        // 统计括号层级，支持 https://example.com/a(b)；转义的括号不参与配对。
        int nesting = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == open) nesting++;
            if (c == close && --nesting == 0) return i;
        }
        return -1;
    }

    private static int findMarker(String text, String marker, int start) {
        for (int i = start; i <= text.length() - marker.length(); i++) {
            if (text.charAt(i) == '\\' && !marker.equals("`")) { i++; continue; }
            if (text.startsWith(marker, i)) {
                // 寻找斜体结束标记时，跳过嵌套的粗体标记。
                if (marker.equals("*") && text.startsWith("**", i)) { i++; continue; }
                return i;
            }
        }
        return -1;
    }

    private static boolean isWebUrl(String value) {
        // 只为具有主机名的 HTTP(S) 绝对地址创建点击事件，其余链接语法保留为原文。
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void ensureLayout() {
        // 文本在构造后不变，只在宽度改变时重新折行，避免每帧重复排版。
        if (layoutWidth == getWidth()) return;
        // 提前更新缓存宽度：末尾校正滚动量时会经 getInnerHeight 再次进入此方法。
        layoutWidth = getWidth();
        renderedLines.clear();
        contentHeight = 0;
        Font font = Minecraft.getInstance().font;
        for (MarkdownLine line : lines) {
            float scale = switch (line.headingLevel()) {
                case 1 -> 1.75f;
                case 2 -> 1.5f;
                case 3 -> 1.3f;
                case 4 -> 1.2f;
                case 5 -> 1.1f;
                default -> 1.0f;
            };
            // font.split 使用未缩放的字体宽度，因此可用屏幕宽度需先除以缩放比例。
            // 保留 FormattedCharSequence 中的样式，供绘制和链接命中检测共同使用。
            int width = Math.max(1, (int)((getWidth() - totalInnerPadding()) / scale));
            List<FormattedCharSequence> wrapped = font.split(line.content(), width);
            // 空行也占一个行高，避免源文本中的段落间隔消失。
            if (wrapped.isEmpty()) wrapped = List.of(FormattedCharSequence.EMPTY);
            int height = (int)Math.ceil(font.lineHeight * scale) + 2;
            for (FormattedCharSequence content : wrapped) {
                renderedLines.add(new RenderLine(content, scale, contentHeight, height));
                contentHeight += height;
            }
            if (line.headingLevel() > 0) contentHeight += 3;
        }
        // 重新折行可能缩短总高度，将原滚动位置限制到新的有效范围内。
        setScrollAmount(scrollAmount());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    public void setColor(int color) { this.color = color; }
    public void setBackgroundVisible(boolean visible) { this.isBackgroundVisible = visible; }
    public void setScrollbarVisible(boolean visible) { this.isScrollbarVisible = visible; }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        if (isBackgroundVisible) renderBorder(graphics, getX(), getY(), getWidth(), getHeight());
    }

    @Override
    protected int getInnerHeight() {
        ensureLayout();
        return contentHeight;
    }

    @Override
    protected double scrollRate() { return 10.0; }

    @Override
    protected boolean scrollbarVisible() {
        return isScrollbarVisible && getMaxScrollAmount() > 0;
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ensureLayout();
        Font font = Minecraft.getInstance().font;
        // 父类已应用滚动平移和裁剪，这里不再减去 scrollAmount，避免重复滚动。
        for (RenderLine line : renderedLines) {
            int y = getY() + innerPadding() + line.y();
            if (!withinContentAreaTopBottom(y, y + line.height())) continue;
            graphics.pose().pushPose();
            // 先定位到行起点再缩放，使缩放只影响文字，不改变控件位置和内边距。
            graphics.pose().translate(getX() + innerPadding(), y, 0);
            graphics.pose().scale(line.scale(), line.scale(), 1);
            graphics.drawString(font, line.content(), 0, 0, color, false);
            graphics.pose().popPose();
        }
    }

    private Style styleAt(double mouseX, double mouseY) {
        // 与父类的裁剪区域保持一致，防止点击边框或已滚出可视区域的链接。
        if (mouseX < getX() + 1 || mouseX >= getX() + getWidth() - 1
                || mouseY < getY() + 1 || mouseY >= getY() + getHeight() - 1) return null;
        ensureLayout();
        // 将屏幕坐标还原为内容坐标：扣除控件位置和内边距，再补回纵向滚动偏移。
        double x = mouseX - getX() - innerPadding();
        double y = mouseY - getY() - innerPadding() + scrollAmount();
        if (x < 0) return null;
        Font font = Minecraft.getInstance().font;
        for (RenderLine line : renderedLines) {
            // 命中范围只包含文字高度，不包含行间距和标题后的额外间距。
            if (y >= line.y() && y < line.y() + font.lineHeight * line.scale()) {
                // 将横坐标还原为未缩放的字体坐标，按字符宽度取得点击处的样式。
                float textX = (float)(x / line.scale());
                if (textX >= font.width(line.content())) return null;
                return font.getSplitter().componentStyleAtWidth(line.content(), (int)textX);
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;
        if (button == 0) {
            Style style = styleAt(mouseX, mouseY);
            var screen = Minecraft.getInstance().screen;
            // 交给游戏处理打开链接，沿用玩家的链接开关和打开前确认设置。
            if (style != null && style.getClickEvent() != null && screen != null
                    && screen.handleComponentClicked(style)) return true;
        }
        // 未处理为链接的点击继续交给父类，以保留滚动条等原有交互。
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
