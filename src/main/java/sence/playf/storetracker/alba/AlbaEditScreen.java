package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
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
    // Map.of()(불변 맵)는 containsKey(null)에서 NullPointerException을 던진다 - 아직 작업을
    // 안 고른 채로 해고된 기록은 entry.job()이 null이라, 서버에서 단가를 받아오기 전(첫 렌더
    // 몇 프레임) 이 화면이 뜨자마자 크래시하는 원인이었다(1.21.4 사용자들의 "/알바설정이 안
    // 먹히고 튕긴다" 신고와 정확히 일치). null 키를 허용하는 HashMap으로 바꿈.
    private Map<String, Integer> wageRates = new HashMap<>();
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

        // "⚙" 기어 기호는 NEXON 폰트에 글리프가 없어서 네모(토푸)로 깨져 보였다 - 폰트가 아는
        // 평범한 한글 텍스트로 바꿈.
        addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - 50, PANEL_Y + 8, 42, 18, "설정",
                () -> client.setScreen(new AlbaSettingsScreen())));

        int rowY = PANEL_Y + LIST_START_Y;
        List<AlbaEntry> active = activeHires();
        for (AlbaEntry entry : active) {
            addButton(new AlbaButton(PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 8, rowY + (ROW_H - EDIT_BUTTON_HEIGHT) / 2,
                    EDIT_BUTTON_WIDTH, EDIT_BUTTON_HEIGHT, "수정",
                    () -> client.setScreen(new AlbaGuiScreen(entry.employer(), entry.employee()))));
            rowY += ROW_H;
        }
        if (active.isEmpty()) {
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

    /** 예전엔 내가 고용한 알바만 보여줬는데, "다른 사람이 고용한 알바는 수정이 안 뜬다"는 요청에
     * 맞춰 지금 일하는 알바 전체를 보여준다(해고처럼 작업 설정도 등록 멤버라면 누구든 고칠 수
     * 있게 서버 쪽도 같이 풀었다). */
    private List<AlbaEntry> activeHires() {
        return AlbaTracker.getCachedHires();
    }

    private int panelHeight() {
        int activeRows = Math.max(1, activeHires().size());
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

        List<AlbaEntry> active = activeHires();
        int rowY = PANEL_Y + LIST_START_Y;
        int textRight = PANEL_X + PANEL_WIDTH - EDIT_BUTTON_WIDTH - 16;
        if (active.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 일하는 알바가 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 10, rowY + 6, COLOR_MUTED);
        } else {
            for (AlbaEntry entry : active) {
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
        int maxTextWidth = Math.max(0, textRight - textX - 4);
        AlbaJob job = AlbaJob.fromName(entry.job());
        String jobLabel = job != null ? job.label() : "미지정";
        String title = active ? entry.employer() + " → " + entry.employee() : entry.employee();
        title = trimWithEllipsis(textRenderer, title, maxTextWidth);
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText(title, true, COLOR_TITLE),
                textX, rowY + 3, COLOR_TITLE);
        Integer rate = entry.job() != null ? wageRates.get(entry.job()) : null;
        String detail = active
                ? jobLabel + " · " + relativeTime(entry.hiredAtMillis())
                : jobLabel + (rate != null ? " · " + rate.toString() + "원/상자" : "");
        detail = trimWithEllipsis(textRenderer, detail, maxTextWidth);
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText(detail, false, COLOR_MUTED), textX, rowY + 15, COLOR_MUTED);

        AlbaZone zone = job == AlbaJob.PUMPKIN ? parseZone(entry.zone()) : null;
        if (zone != null && active) {
            int swatchX = textRight - ZONE_SWATCH_SIZE;
            int swatchY = rowY + (ROW_H - ZONE_SWATCH_SIZE) / 2;
            context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
            drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
        }
    }

    /** 폭이 넘치면 "..."을 붙여서 잘린 게 보이게 한다 - HUD 패널에서 겪었던 글자-아이콘 겹침과
     * 같은 문제를 여기서도 미리 막는다. */
    private static String trimWithEllipsis(net.minecraft.client.font.TextRenderer tr, String text, int maxWidth) {
        if (tr.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        String trimmed = tr.trimToWidth(text, Math.max(0, maxWidth - tr.getWidth(ellipsis)));
        return trimmed + ellipsis;
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
