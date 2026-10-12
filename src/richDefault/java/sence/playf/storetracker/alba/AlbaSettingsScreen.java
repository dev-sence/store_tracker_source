package sence.playf.storetracker.alba;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 알바 HUD 설정 화면 - 표시여부와 위치(버튼으로 미세 이동)만 다룬다. 알바별 작업 수정은
 * {@link AlbaEditScreen}에서 하고(2단계 구조), 이 화면은 그 "⚙ 설정" 버튼으로 들어온다. 버튼만
 * 써서 Screen의 커스텀 마우스 클릭 오버라이드가 필요 없다(그 API가 1.21.9부터 바뀌어서 전 버전
 * 호환이 안 됐음 - 버튼 클릭은 엔진이 알아서 처리하므로 버전에 상관없이 동작한다). */
public class AlbaSettingsScreen extends Screen {
    private static final int PANEL_X = 20;
    private static final int PANEL_Y = 20;
    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 150;
    private static final int NUDGE_STEP = 4;
    private static final int COLOR_BG = 0xE61B1B1B;
    private static final int COLOR_BORDER = 0x66FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;

    private ButtonWidget visibleToggleButton;

    public AlbaSettingsScreen() {
        super(Text.literal("알바 설정"));
    }

    @Override
    protected void init() {
        AlbaTracker.pollNow();

        visibleToggleButton = ButtonWidget.builder(Text.literal(""), b -> {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudVisible = !settings.hudVisible;
            settings.save();
        }).dimensions(PANEL_X + 150, PANEL_Y + 40, 90, 20).build();
        addDrawableChild(visibleToggleButton);

        int nudgeY = PANEL_Y + 90;
        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> AlbaSettings.get().nudgePosition(0, -NUDGE_STEP))
                .dimensions(PANEL_X + 180, nudgeY, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> AlbaSettings.get().nudgePosition(-NUDGE_STEP, 0))
                .dimensions(PANEL_X + 158, nudgeY + 18, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> AlbaSettings.get().nudgePosition(NUDGE_STEP, 0))
                .dimensions(PANEL_X + 202, nudgeY + 18, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> AlbaSettings.get().nudgePosition(0, NUDGE_STEP))
                .dimensions(PANEL_X + 180, nudgeY + 36, 20, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), b -> close())
                .dimensions(PANEL_X + PANEL_WIDTH - 54, PANEL_Y + PANEL_HEIGHT - 26, 44, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + PANEL_HEIGHT, COLOR_BG);
        drawBorder(context, PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, Text.literal("알바 설정"), PANEL_X + 8, PANEL_Y + 8, COLOR_TITLE);

        AlbaSettings settings = AlbaSettings.get();
        visibleToggleButton.setMessage(Text.literal("표시: " + (settings.hudVisible ? "ON" : "OFF")));
        context.drawTextWithShadow(textRenderer, Text.literal("알바 현황 표시"), PANEL_X + 8, PANEL_Y + 46, COLOR_MUTED);

        context.drawTextWithShadow(textRenderer, Text.literal("위치 (지금: " + settings.hudX + ", " + settings.hudY + ")"),
                PANEL_X + 8, PANEL_Y + 96, COLOR_MUTED);

        super.render(context, mouseX, mouseY, delta);
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
