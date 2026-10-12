package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 바닐라 회색 텍스처 버튼 대신 쓰는 단색 커스텀 버튼. {@code PressableWidget}을 직접 상속하려 했으나
 * onPress/renderWidget의 시그니처와 abstract 여부가 1.21.4/1.21.9/1.21.11에서 전부 달라서(심지어
 * 1.21.11에서는 renderWidget이 final이라 아예 오버라이드가 막혀 있음) 포기하고, 대신 실제
 * 클릭 처리는 입증된 호환성을 가진 바닐라 {@link ButtonWidget}(라벨은 비워서 안 보이게)에 맡기고,
 * 우리 모양(색 배경+테두리+NEXON 폰트 글자)은 화면의 render()에서 버튼 위에 따로 덧그린다
 * (playf의 PlayfButton도 같은 이유로 위젯을 상속하지 않고 직접 그리는 방식을 쓴다).
 * 사용법: addDrawableChild(button.widget())로 클릭을 등록하고, render()에서 button.render(context)를
 * 호출해 버튼 영역 위에 우리 모양을 그린다(바닐라 배경을 완전히 덮어서 안 보이게 됨).
 */
public final class AlbaButton {
    private static final int COLOR_BG = 0xFF2A2A2A;
    private static final int COLOR_BG_HOVER = 0xFF3A3F47;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_BORDER_HOVER = 0xFF6FB0E8;
    private static final int COLOR_TEXT = 0xFFECECEC;
    private static final int COLOR_TEXT_DISABLED = 0xFF6A6A6A;

    private final ButtonWidget widget;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final boolean bold;
    private int textIndent = -1;
    private String label;

    public AlbaButton(int x, int y, int width, int height, String label, Runnable onClick) {
        this(x, y, width, height, label, false, onClick);
    }

    public AlbaButton(int x, int y, int width, int height, String label, boolean bold, Runnable onClick) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.label = label;
        this.bold = bold;
        this.widget = ButtonWidget.builder(Text.empty(), b -> onClick.run()).dimensions(x, y, width, height).build();
    }

    /** 기본은 가운데 정렬 - 아이콘을 왼쪽에 따로 그려 넣는 버튼은 텍스트를 그만큼 오른쪽으로
     * 밀어야 해서, 왼쪽 여백(px)을 직접 지정할 수 있게 한다. */
    public AlbaButton withTextIndent(int indent) {
        this.textIndent = indent;
        return this;
    }

    /** addDrawableChild(button.widget())로 등록해야 실제 클릭이 처리된다. */
    public ButtonWidget widget() {
        return widget;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setActive(boolean active) {
        widget.active = active;
    }

    /** 버튼(ButtonWidget) 그리기가 끝난 뒤(super.render 이후) 호출해서 바닐라 배경을 우리 모양으로
     * 완전히 덮어 그린다. */
    public void render(DrawContext context) {
        boolean active = widget.active;
        boolean hover = widget.isHovered() || widget.isFocused();
        int bg = hover && active ? COLOR_BG_HOVER : COLOR_BG;
        int border = active && hover ? COLOR_BORDER_HOVER : COLOR_BORDER;
        int textColor = active ? COLOR_TEXT : COLOR_TEXT_DISABLED;

        context.fill(x, y, x + width, y + height, bg);
        drawBorder(context, x, y, width, height, border);

        MinecraftClient client = MinecraftClient.getInstance();
        Text styled = AlbaPlatform.styledText(label, bold, textColor);
        int textY = y + (height - client.textRenderer.fontHeight) / 2;
        int textX;
        if (textIndent >= 0) {
            textX = x + textIndent;
        } else {
            textX = x + (width - client.textRenderer.getWidth(styled)) / 2;
        }
        context.drawTextWithShadow(client.textRenderer, styled, textX, textY, textColor);
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }
}
