package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * "/알바설정" 또는 키바인드를 누르면 가장 먼저 뜨는 화면 - 내가 고용한 알바 목록과 각자의 "수정"
 * 버튼만 보여준다. HUD 표시여부/크기/위치 같은 설정은 오른쪽 위 "설정" 버튼을 눌러야 따로 뜬다
 * (2단계 구조). 버튼(ClickableWidget)만 써서 전 버전에서 공통으로 컴파일된다(마우스 클릭 API가
 * 1.21.9부터 바뀌었는데, 버튼 클릭은 엔진이 알아서 처리해줘서 버전에 상관없다). 행을 이름/상세
 * 두 줄로 나눠서 닉네임이 길어도 구역 표시·버튼과 안 겹치게 한다.
 */
public class AlbaEditScreen extends Screen {
    private static final int PANEL_X = 16;
    private static final int PANEL_Y = 16;
    private static final int PANEL_WIDTH = 260;
    private static final int ROW_H = 30;
    private static final int LIST_START_Y = 40;
    private static final int COLOR_BG = 0xF01B1B1B;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int FACE_SIZE = 14;
    private static final int ZONE_SWATCH_SIZE = 8;
    private static final int EDIT_BUTTON_WIDTH = 40;
    private static final int EDIT_BUTTON_HEIGHT = 18;

    private final List<AlbaButton> albaButtons = new ArrayList<>();

    public AlbaEditScreen() {
        super(Text.literal("알바 수정"));
    }

    @Override
    protected void init() {
        AlbaTracker.pollNow();
        albaButtons.clear();

        addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - 26, PANEL_Y + 8, 18, 18, "⚙",
                () -> client.setScreen(new AlbaSettingsScreen())));

        int rowY = PANEL_Y + LIST_START_Y;
        for (AlbaEntry entry : myHires()) {
            addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 8, rowY + (ROW_H - EDIT_BUTTON_HEIGHT) / 2,
                    EDIT_BUTTON_WIDTH, EDIT_BUTTON_HEIGHT, "수정",
                    () -> client.setScreen(new AlbaGuiScreen(entry.employer(), entry.employee()))));
            rowY += ROW_H;
        }

        addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - 48, PANEL_Y + panelHeight() - 26, 40, 18, "닫기", this::close));
    }

    private void addButton(AlbaButton button) {
        albaButtons.add(button);
        addDrawableChild(button.widget());
    }

    private List<AlbaEntry> myHires() {
        MinecraftClient client = MinecraftClient.getInstance();
        String me = client.player != null ? client.player.getName().getString() : "";
        return AlbaTracker.getCachedHires().stream()
                .filter(e -> e.employer().equalsIgnoreCase(me))
                .toList();
    }

    private int panelHeight() {
        return LIST_START_Y + Math.max(1, myHires().size()) * ROW_H + 30;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int height = panelHeight();
        context.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + height, COLOR_BG);
        drawBorder(context, PANEL_X, PANEL_Y, PANEL_WIDTH, height, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("알바 수정", true, COLOR_TITLE), PANEL_X + 10, PANEL_Y + 12, COLOR_TITLE);

        List<AlbaEntry> mine = myHires();
        int rowY = PANEL_Y + LIST_START_Y;
        if (mine.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 고용 중인 알바가 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 10, rowY + 6, COLOR_MUTED);
        } else {
            int textRight = PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 16;
            for (AlbaEntry entry : mine) {
                int faceY = rowY + (ROW_H - FACE_SIZE) / 2;
                Identifier face = PlayerFaceFetcher.getFaceTexture(entry.employee());
                if (face != null) {
                    AlbaPlatform.drawFace(context, face, PANEL_X + 10, faceY, FACE_SIZE);
                } else {
                    context.fill(PANEL_X + 10, faceY, PANEL_X + 10 + FACE_SIZE, faceY + FACE_SIZE, 0xFF3C3C3C);
                }

                int textX = PANEL_X + 10 + FACE_SIZE + 6;
                AlbaJob job = AlbaJob.fromName(entry.job());
                String jobLabel = job != null ? job.label() : "미지정";
                context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText(entry.employee(), true, COLOR_TITLE),
                        textX, rowY + 3, COLOR_TITLE);
                context.drawTextWithShadow(textRenderer,
                        AlbaPlatform.styledText(jobLabel + " · " + relativeTime(entry.hiredAtMillis()), false, COLOR_MUTED),
                        textX, rowY + 15, COLOR_MUTED);

                AlbaZone zone = job == AlbaJob.PUMPKIN ? parseZone(entry.zone()) : null;
                if (zone != null) {
                    int swatchX = textRight - ZONE_SWATCH_SIZE;
                    int swatchY = rowY + (ROW_H - ZONE_SWATCH_SIZE) / 2;
                    context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
                    drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
                }
                rowY += ROW_H;
            }
        }

        super.render(context, mouseX, mouseY, delta);
        for (AlbaButton button : albaButtons) {
            button.render(context);
        }
    }

    private static AlbaZone parseZone(String name) {
        if (name == null) {
            return null;
        }
        try {
            return AlbaZone.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String relativeTime(long hiredAtMillis) {
        if (hiredAtMillis <= 0L) {
            return "방금";
        }
        long deltaSeconds = Math.max(0L, (System.currentTimeMillis() - hiredAtMillis) / 1000L);
        if (deltaSeconds < 60L) {
            return "방금";
        }
        long minutes = deltaSeconds / 60L;
        if (minutes < 60L) {
            return minutes + "분 전";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "시간 전";
        }
        return (hours / 24L) + "일 전";
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
