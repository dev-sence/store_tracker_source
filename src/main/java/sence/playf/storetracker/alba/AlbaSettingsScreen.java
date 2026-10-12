package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** "/알바설정"으로 여는 알바 HUD 설정 화면 - 표시여부, 위치(버튼으로 미세 이동), 그리고 내가 지금
 * 고용 중인 알바들의 작업을 다시 고를 수 있는 목록. 버튼(ClickableWidget) 기반으로만 상호작용해서
 * Screen의 커스텀 마우스 클릭 오버라이드가 필요 없다 (그 API가 1.21.9부터 바뀌어서 전 버전 호환이
 * 안 됐음 - 버튼 클릭은 엔진이 알아서 처리하므로 버전에 상관없이 동작한다). */
public class AlbaSettingsScreen extends Screen {
    private static final int PANEL_X = 20;
    private static final int PANEL_Y = 20;
    private static final int PANEL_WIDTH = 260;
    private static final int NUDGE_STEP = 4;
    private static final int COLOR_BG = 0xF0101010;
    private static final int COLOR_BORDER = 0xFF4A4A4A;

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

        int rowY = PANEL_Y + 150;
        for (AlbaEntry entry : myHires()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("수정"), b ->
                            client.setScreen(new AlbaGuiScreen(entry.employer(), entry.employee())))
                    .dimensions(PANEL_X + PANEL_WIDTH - 50, rowY, 40, 16).build());
            rowY += 18;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), b -> close())
                .dimensions(PANEL_X + PANEL_WIDTH - 54, PANEL_Y + panelHeight() - 26, 44, 20).build());
    }

    private List<AlbaEntry> myHires() {
        MinecraftClient client = MinecraftClient.getInstance();
        String me = client.player != null ? client.player.getName().getString() : "";
        return AlbaTracker.getCachedHires().stream()
                .filter(e -> e.employer().equalsIgnoreCase(me))
                .toList();
    }

    private int panelHeight() {
        return 150 + Math.max(1, myHires().size()) * 18 + 30;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int height = panelHeight();
        context.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + height, COLOR_BG);
        drawBorder(context, PANEL_X, PANEL_Y, PANEL_WIDTH, height, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, Text.literal("알바 설정"), PANEL_X + 8, PANEL_Y + 8, 0xFFFFFFFF);

        AlbaSettings settings = AlbaSettings.get();
        visibleToggleButton.setMessage(Text.literal("표시: " + (settings.hudVisible ? "ON" : "OFF")));
        context.drawTextWithShadow(textRenderer, Text.literal("알바 현황 표시"), PANEL_X + 8, PANEL_Y + 46, 0xFFAAAAAA);

        context.drawTextWithShadow(textRenderer, Text.literal("위치 (지금: " + settings.hudX + ", " + settings.hudY + ")"),
                PANEL_X + 8, PANEL_Y + 96, 0xFFAAAAAA);

        List<AlbaEntry> mine = myHires();
        if (mine.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 고용 중인 알바가 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 8, PANEL_Y + 150, 0xFFAAAAAA);
        } else {
            int rowY = PANEL_Y + 150;
            for (AlbaEntry entry : mine) {
                AlbaJob job = AlbaJob.fromName(entry.job());
                String jobLabel = job != null ? job.label() : "미지정";
                context.drawTextWithShadow(textRenderer,
                        Text.literal(entry.employee() + " · " + jobLabel), PANEL_X + 8, rowY + 4, 0xFFFFFFFF);
                rowY += 18;
            }
        }

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
