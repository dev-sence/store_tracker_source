package sence.playf.storetracker.alba;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** 1.21.11 전용 - playf의 FarmHudScreen과 같은 방식: 진짜 HUD 패널을 실제 화면 위치에 그대로
 * 그려서, 그 패널 자체를 드래그하면 바로 hudX/hudY가 바뀐다(설정창 안의 축소 미리보기가 아님).
 * 알바별 수정은 {@link AlbaEditScreen}에서 하고, 이 화면은 표시여부/크기/위치만 다룬다(2단계
 * 구조) - "알바 수정" 화면의 "⚙ 설정" 버튼으로 들어온다. */
public class AlbaSettingsScreen extends Screen {
    private static final int PANEL_WIDTH = 210;
    private static final int COLOR_BG = 0xF01B1B1B;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF6FB0E8;
    private static final int DIM_COLOR = 0x80000000;
    private static final int PANEL_HEIGHT = 122;

    private AlbaButton visibleToggleButton;
    private final List<AlbaButton> albaButtons = new ArrayList<>();
    private boolean draggingHud = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private int dragStartHudX;
    private int dragStartHudY;

    public AlbaSettingsScreen() {
        super(Text.literal("알바 설정"));
    }

    private int panelX() {
        return 16;
    }

    private int panelY() {
        return height - PANEL_HEIGHT - 16;
    }

    @Override
    protected void init() {
        AlbaTracker.pollNow();
        int px = panelX();
        int py = panelY();

        visibleToggleButton = new AlbaButton(px + PANEL_WIDTH - 86, py + 36, 76, 18, "", () -> {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudVisible = !settings.hudVisible;
            settings.save();
        });
        addButton(visibleToggleButton);
        addButton(new AlbaButton(px + PANEL_WIDTH - 86, py + 68, 20, 18, "-", () -> AlbaSettings.get().adjustScale(-10)));
        addButton(new AlbaButton(px + PANEL_WIDTH - 62, py + 68, 20, 18, "+", () -> AlbaSettings.get().adjustScale(10)));
        addButton(new AlbaButton(px + PANEL_WIDTH - 48, py + PANEL_HEIGHT - 26, 40, 18, "닫기", this::close));
    }

    private void addButton(AlbaButton button) {
        albaButtons.add(button);
        addDrawableChild(button.widget());
    }

    /** 지금 실제 화면에 떠 있는 HUD 패널의 화면상 사각형(스케일 적용 후) - 드래그 판정과 강조
     * 테두리에 함께 쓴다. */
    private int[] hudScreenRect() {
        AlbaSettings settings = AlbaSettings.get();
        int[] size = AlbaHudOverlay.measure(textRenderer);
        float scale = settings.scalePercent / 100f;
        int w = Math.round(size[0] * scale);
        int h = Math.round(size[1] * scale);
        return new int[]{settings.hudX, settings.hudY, w, h};
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, DIM_COLOR);

        AlbaSettings settings = AlbaSettings.get();
        int[] rect = hudScreenRect();
        boolean hover = mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3];
        if (draggingHud || hover) {
            drawBorder(context, rect[0] - 2, rect[1] - 2, rect[2] + 4, rect[3] + 4, COLOR_ACCENT);
        }
        if (settings.hudVisible) {
            float scale = settings.scalePercent / 100f;
            AlbaPlatform.withHudScale(context, settings.hudX, settings.hudY, scale,
                    () -> AlbaHudOverlay.draw(context, textRenderer, settings.hudX, settings.hudY));
        } else {
            AlbaHudOverlay.draw(context, textRenderer, settings.hudX, settings.hudY);
        }

        int px = panelX();
        int py = panelY();
        context.fill(px, py, px + PANEL_WIDTH, py + PANEL_HEIGHT, COLOR_BG);
        drawBorder(context, px, py, PANEL_WIDTH, PANEL_HEIGHT, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("알바 설정", true, COLOR_TITLE), px + 10, py + 10, COLOR_TITLE);
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("위 패널을 드래그해서 옮기세요", false, COLOR_ACCENT),
                px + 10, py + 22, COLOR_ACCENT);

        visibleToggleButton.setLabel(settings.hudVisible ? "ON" : "OFF");
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("표시", false, COLOR_MUTED), px + 10, py + 42, COLOR_MUTED);
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("크기 " + settings.scalePercent + "%", false, COLOR_MUTED),
                px + 10, py + 74, COLOR_MUTED);

        super.render(context, mouseX, mouseY, delta);
        for (AlbaButton button : albaButtons) {
            button.render(context);
        }
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int[] rect = hudScreenRect();
        double mouseX = click.x();
        double mouseY = click.y();
        if (mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
            draggingHud = true;
            dragStartMouseX = (int) mouseX;
            dragStartMouseY = (int) mouseY;
            AlbaSettings settings = AlbaSettings.get();
            dragStartHudX = settings.hudX;
            dragStartHudY = settings.hudY;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingHud) {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudX = Math.max(0, dragStartHudX + (int) Math.round(click.x() - dragStartMouseX));
            settings.hudY = Math.max(0, dragStartHudY + (int) Math.round(click.y() - dragStartMouseY));
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingHud) {
            draggingHud = false;
            AlbaSettings.get().save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
