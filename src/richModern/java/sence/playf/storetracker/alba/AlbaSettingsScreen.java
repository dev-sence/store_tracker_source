package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;

/** 1.21.11 전용 - 새 Click 기반 마우스 API로 HUD를 실제로 드래그해서 옮길 수 있다
 * (미리보기가 지금 HUD랑 똑같은 내용을 그려서, 그걸 그대로 끄는 느낌). 크기조절·얼굴 아이콘도
 * 진짜로 동작한다. "/알바설정" 또는 키바인드로 연다. */
public class AlbaSettingsScreen extends Screen {
    private static final int PANEL_X = 20;
    private static final int PANEL_Y = 20;
    private static final int PANEL_WIDTH = 360;
    private static final int ROW_H = 24;
    private static final int COLOR_BG = 0xF01B1B1B;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int FACE_SIZE = 16;
    private static final int ZONE_SWATCH_SIZE = 10;

    private ButtonWidget visibleToggleButton;
    private boolean draggingPreview = false;

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
        }).dimensions(PANEL_X + PANEL_WIDTH - 130, PANEL_Y + 44, 110, 22).build();
        addDrawableChild(visibleToggleButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> AlbaSettings.get().adjustScale(-10))
                .dimensions(PANEL_X + PANEL_WIDTH - 130, PANEL_Y + 78, 24, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> AlbaSettings.get().adjustScale(10))
                .dimensions(PANEL_X + PANEL_WIDTH - 102, PANEL_Y + 78, 24, 22).build());

        int rowY = myListStartY();
        for (AlbaEntry entry : myHires()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("수정"), b ->
                            client.setScreen(new AlbaGuiScreen(entry.employer(), entry.employee())))
                    .dimensions(PANEL_X + PANEL_WIDTH - 60, rowY, 48, 20).build());
            rowY += ROW_H;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), b -> close())
                .dimensions(PANEL_X + PANEL_WIDTH - 64, PANEL_Y + panelHeight() - 32, 52, 22).build());
    }

    private List<AlbaEntry> myHires() {
        MinecraftClient client = MinecraftClient.getInstance();
        String me = client.player != null ? client.player.getName().getString() : "";
        return AlbaTracker.getCachedHires().stream()
                .filter(e -> e.employer().equalsIgnoreCase(me))
                .toList();
    }

    private int[] previewSize() {
        return AlbaHudOverlay.measure(textRenderer);
    }

    private int previewAreaY() {
        return PANEL_Y + 112;
    }

    private int myListStartY() {
        int[] preview = previewSize();
        return previewAreaY() + preview[1] + 20;
    }

    private int panelHeight() {
        return myListStartY() - PANEL_Y + Math.max(1, myHires().size()) * ROW_H + 40;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int height = panelHeight();
        context.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + height, COLOR_BG);
        drawBorder(context, PANEL_X, PANEL_Y, PANEL_WIDTH, height, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, Text.literal("알바 설정"), PANEL_X + 12, PANEL_Y + 12, COLOR_TITLE);

        AlbaSettings settings = AlbaSettings.get();
        visibleToggleButton.setMessage(Text.literal("표시: " + (settings.hudVisible ? "ON" : "OFF")));
        context.drawTextWithShadow(textRenderer, Text.literal("알바 현황 표시"), PANEL_X + 12, PANEL_Y + 50, COLOR_MUTED);

        context.drawTextWithShadow(textRenderer, Text.literal("HUD 크기: " + settings.scalePercent + "%"),
                PANEL_X + 12, PANEL_Y + 84, COLOR_MUTED);

        context.drawTextWithShadow(textRenderer, Text.literal("위치 - 아래를 그대로 드래그해서 옮기세요"),
                PANEL_X + 12, previewAreaY() - 14, COLOR_MUTED);

        int pvX = PANEL_X + 12;
        int pvY = previewAreaY();
        int[] preview = previewSize();
        boolean hover = mouseX >= pvX && mouseX < pvX + preview[0] && mouseY >= pvY && mouseY < pvY + preview[1];
        if (draggingPreview || hover) {
            drawBorder(context, pvX - 2, pvY - 2, preview[0] + 4, preview[1] + 4, 0xFF6FB0E8);
        }
        AlbaHudOverlay.draw(context, textRenderer, pvX, pvY);

        List<AlbaEntry> mine = myHires();
        int rowY = myListStartY();
        if (mine.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 고용 중인 알바가 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 12, rowY + 4, COLOR_MUTED);
        } else {
            for (AlbaEntry entry : mine) {
                Identifier face = PlayerFaceFetcher.getFaceTexture(entry.employee());
                if (face != null) {
                    AlbaPlatform.drawFace(context, face, PANEL_X + 12, rowY + 2, FACE_SIZE);
                } else {
                    context.fill(PANEL_X + 12, rowY + 2, PANEL_X + 12 + FACE_SIZE, rowY + 2 + FACE_SIZE, 0xFF3C3C3C);
                }

                AlbaJob job = AlbaJob.fromName(entry.job());
                String jobLabel = job != null ? job.label() : "미지정";
                context.drawTextWithShadow(textRenderer,
                        Text.literal(entry.employee() + " · " + jobLabel + " · " + relativeTime(entry.hiredAtMillis()) + " 고용"),
                        PANEL_X + 12 + FACE_SIZE + 6, rowY + 6, COLOR_TITLE);

                AlbaZone zone = job == AlbaJob.PUMPKIN ? parseZone(entry.zone()) : null;
                if (zone != null) {
                    int swatchX = PANEL_X + PANEL_WIDTH - 60 - ZONE_SWATCH_SIZE - 8;
                    int swatchY = rowY + 7;
                    context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
                    drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
                }
                rowY += ROW_H;
            }
        }

        super.render(context, mouseX, mouseY, delta);
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
    public boolean mouseClicked(Click click, boolean doubled) {
        int pvX = PANEL_X + 12;
        int pvY = previewAreaY();
        int[] preview = previewSize();
        double mouseX = click.x();
        double mouseY = click.y();
        if (mouseX >= pvX && mouseX < pvX + preview[0] && mouseY >= pvY && mouseY < pvY + preview[1]) {
            draggingPreview = true;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingPreview) {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudX = Math.max(0, settings.hudX + (int) offsetX);
            settings.hudY = Math.max(0, settings.hudY + (int) offsetY);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingPreview) {
            draggingPreview = false;
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
