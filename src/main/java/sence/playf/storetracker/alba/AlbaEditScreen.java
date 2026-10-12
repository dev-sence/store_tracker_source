package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "/알바설정" 또는 키바인드를 누르면 가장 먼저 뜨는 화면 - 내가 고용한 알바 목록(수정 버튼)과,
 * 해고는 됐는데 아직 "몇 상자 채웠는지"를 안 적은 기록을 적을 수 있는 목록을 보여준다. 몇 상자
 * 기록은 여기서만(이 창을 열어야만) 적을 수 있다 - 서버 /api/alba/record가 의도적으로 요구하는
 * 제약. HUD 표시여부/크기/위치 같은 설정은 오른쪽 위 "⚙" 버튼을 눌러야 따로 뜬다(2단계 구조).
 * 버튼/텍스트필드(ClickableWidget)만 써서 전 버전에서 공통으로 컴파일된다(마우스 클릭 API가
 * 1.21.9부터 바뀌었는데, 버튼 클릭은 엔진이 알아서 처리해줘서 버전에 상관없다).
 */
public class AlbaEditScreen extends Screen {
    private static final int PANEL_X = 16;
    private static final int PANEL_Y = 16;
    private static final int PANEL_WIDTH = 270;
    private static final int ROW_H = 30;
    private static final int LIST_START_Y = 40;
    private static final int COLOR_BG = 0xF01B1B1B;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF6FB0E8;
    private static final int FACE_SIZE = 14;
    private static final int ZONE_SWATCH_SIZE = 8;
    private static final int EDIT_BUTTON_WIDTH = 40;
    private static final int EDIT_BUTTON_HEIGHT = 18;
    private static final int BOX_FIELD_WIDTH = 34;

    private final List<AlbaButton> albaButtons = new ArrayList<>();
    private List<AlbaEntry> pendingRecords = List.of();
    private Map<String, Integer> wageRates = Map.of();
    private int pendingListStartY;

    public AlbaEditScreen() {
        super(Text.literal("알바 수정"));
    }

    @Override
    protected void init() {
        AlbaTracker.pollNow();
        String me = myName();
        AlbaTracker.getBridge().fetchPendingRecords(me, list -> {
            pendingRecords = list;
            if (client != null && client.currentScreen == this) {
                rebuild();
            }
        });
        AlbaTracker.getBridge().fetchWageRates(rates -> wageRates = rates);
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        albaButtons.clear();

        addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - 26, PANEL_Y + 8, 18, 18, "⚙",
                () -> client.setScreen(new AlbaSettingsScreen())));

        int rowY = PANEL_Y + LIST_START_Y;
        List<AlbaEntry> mine = myHires();
        for (AlbaEntry entry : mine) {
            addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 8, rowY + (ROW_H - EDIT_BUTTON_HEIGHT) / 2,
                    EDIT_BUTTON_WIDTH, EDIT_BUTTON_HEIGHT, "수정",
                    () -> client.setScreen(new AlbaGuiScreen(entry.employer(), entry.employee()))));
            rowY += ROW_H;
        }
        if (mine.isEmpty()) {
            rowY += ROW_H;
        }

        pendingListStartY = rowY + 18;
        rowY = pendingListStartY + 16;
        for (AlbaEntry entry : pendingRecords) {
            int fieldY = rowY + (ROW_H - 16) / 2;
            TextFieldWidget field = new TextFieldWidget(textRenderer,
                    PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - BOX_FIELD_WIDTH - 14, fieldY, BOX_FIELD_WIDTH, 16,
                    Text.literal("상자 수"));
            field.setMaxLength(4);
            addDrawableChild(field);

            int finalRowY = rowY;
            addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 8, finalRowY + (ROW_H - EDIT_BUTTON_HEIGHT) / 2,
                    EDIT_BUTTON_WIDTH, EDIT_BUTTON_HEIGHT, "저장", () -> submitRecord(entry, field)));
            rowY += ROW_H;
        }

        addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - 48, PANEL_Y + panelHeight() - 26, 40, 18, "닫기", this::close));
    }

    private void submitRecord(AlbaEntry entry, TextFieldWidget field) {
        String text = field.getText().trim();
        int boxCount;
        try {
            boxCount = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return;
        }
        if (boxCount < 0) {
            return;
        }
        AlbaTracker.getBridge().record(entry.id(), boxCount, null, () -> {
            pendingRecords = pendingRecords.stream().filter(e -> e.id() != entry.id()).toList();
            if (client != null && client.currentScreen == this) {
                rebuild();
            }
        });
    }

    private void addButton(AlbaButton button) {
        albaButtons.add(button);
        addDrawableChild(button.widget());
    }

    private String myName() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null ? client.player.getName().getString() : "";
    }

    private List<AlbaEntry> myHires() {
        String me = myName();
        return AlbaTracker.getCachedHires().stream()
                .filter(e -> e.employer().equalsIgnoreCase(me))
                .toList();
    }

    private int panelHeight() {
        int activeRows = Math.max(1, myHires().size());
        int base = LIST_START_Y + activeRows * ROW_H + 18;
        int pendingRows = pendingRecords.size();
        return base + 16 + pendingRows * ROW_H + 30;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int height = panelHeight();
        context.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + height, COLOR_BG);
        drawBorder(context, PANEL_X, PANEL_Y, PANEL_WIDTH, height, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("알바 수정", true, COLOR_TITLE), PANEL_X + 10, PANEL_Y + 12, COLOR_TITLE);

        List<AlbaEntry> mine = myHires();
        int rowY = PANEL_Y + LIST_START_Y;
        int textRight = PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 16;
        if (mine.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 고용 중인 알바가 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 10, rowY + 6, COLOR_MUTED);
        } else {
            for (AlbaEntry entry : mine) {
                drawPersonRow(context, entry, rowY, textRight, true);
                rowY += ROW_H;
            }
        }

        int pendingY = pendingListStartY;
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("기록 대기 중 (몇 상자?)", true, COLOR_ACCENT),
                PANEL_X + 10, pendingY, COLOR_ACCENT);
        rowY = pendingY + 16;
        if (pendingRecords.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("대기 중인 기록이 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 10, rowY + 6, COLOR_MUTED);
        } else {
            for (AlbaEntry entry : pendingRecords) {
                drawPersonRow(context, entry, rowY, textRight, false);
                rowY += ROW_H;
            }
        }

        super.render(context, mouseX, mouseY, delta);
        for (AlbaButton button : albaButtons) {
            button.render(context);
        }
    }

    private void drawPersonRow(DrawContext context, AlbaEntry entry, int rowY, int textRight, boolean active) {
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
        String detail = active
                ? jobLabel + " · " + relativeTime(entry.hiredAtMillis())
                : jobLabel + (wageRates.containsKey(entry.job()) ? " · " + wageRates.get(entry.job()) + "원/상자" : "");
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText(detail, false, COLOR_MUTED), textX, rowY + 15, COLOR_MUTED);

        AlbaZone zone = job == AlbaJob.PUMPKIN ? parseZone(entry.zone()) : null;
        if (zone != null && active) {
            int swatchX = textRight - ZONE_SWATCH_SIZE;
            int swatchY = rowY + (ROW_H - ZONE_SWATCH_SIZE) / 2;
            context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
            drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
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
