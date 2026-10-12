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

/** 1.21.11 전용 - HUD 위치를 실제로 드래그해서 옮길 수 있고(새 Click 기반 마우스 API), 크기조절·
 * 얼굴 아이콘도 진짜로 동작한다. "/알바설정" 또는 키바인드로 연다. */
public class AlbaSettingsScreen extends Screen {
    private static final int PANEL_X = 20;
    private static final int PANEL_Y = 20;
    private static final int PANEL_WIDTH = 280;
    private static final int PREVIEW_SCALE_DIV = 4;
    private static final int PREVIEW_W = 50;
    private static final int PREVIEW_H = 18;
    private static final int COLOR_BG = 0xE61B1B1B;
    private static final int COLOR_BORDER = 0x66FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int FACE_SIZE = 12;
    private static final int ZONE_SWATCH_SIZE = 8;

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
        }).dimensions(PANEL_X + 170, PANEL_Y + 40, 90, 20).build();
        addDrawableChild(visibleToggleButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> AlbaSettings.get().adjustScale(-10))
                .dimensions(PANEL_X + 170, PANEL_Y + 66, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> AlbaSettings.get().adjustScale(10))
                .dimensions(PANEL_X + 194, PANEL_Y + 66, 20, 20).build());

        int rowY = PANEL_Y + 160;
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
        return 160 + Math.max(1, myHires().size()) * 18 + 30;
    }

    private int previewX() {
        return PANEL_X + 8 + AlbaSettings.get().hudX / PREVIEW_SCALE_DIV;
    }

    private int previewY() {
        return PANEL_Y + 118 + AlbaSettings.get().hudY / PREVIEW_SCALE_DIV;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int height = panelHeight();
        context.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + height, COLOR_BG);
        drawBorder(context, PANEL_X, PANEL_Y, PANEL_WIDTH, height, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, Text.literal("알바 설정"), PANEL_X + 8, PANEL_Y + 8, COLOR_TITLE);

        AlbaSettings settings = AlbaSettings.get();
        visibleToggleButton.setMessage(Text.literal("표시: " + (settings.hudVisible ? "ON" : "OFF")));
        context.drawTextWithShadow(textRenderer, Text.literal("알바 현황 표시"), PANEL_X + 8, PANEL_Y + 46, COLOR_MUTED);

        context.drawTextWithShadow(textRenderer, Text.literal("HUD 크기: " + settings.scalePercent + "%"),
                PANEL_X + 8, PANEL_Y + 72, COLOR_MUTED);

        context.drawTextWithShadow(textRenderer, Text.literal("위치: 아래 네모를 드래그하세요"), PANEL_X + 8, PANEL_Y + 100, COLOR_MUTED);
        int pvX = previewX();
        int pvY = previewY();
        boolean hover = mouseX >= pvX && mouseX < pvX + PREVIEW_W && mouseY >= pvY && mouseY < pvY + PREVIEW_H;
        context.fill(pvX, pvY, pvX + PREVIEW_W, pvY + PREVIEW_H, draggingPreview || hover ? 0xFF3B82F6 : 0xFF2A2A2A);
        drawBorder(context, pvX, pvY, PREVIEW_W, PREVIEW_H, COLOR_BORDER);

        List<AlbaEntry> mine = myHires();
        if (mine.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 고용 중인 알바가 없어요").formatted(Formatting.GRAY),
                    PANEL_X + 8, PANEL_Y + 160, COLOR_MUTED);
        } else {
            int rowY = PANEL_Y + 160;
            for (AlbaEntry entry : mine) {
                Identifier face = PlayerFaceFetcher.getFaceTexture(entry.employee());
                if (face != null) {
                    AlbaPlatform.drawFace(context, face, PANEL_X + 8, rowY, FACE_SIZE);
                } else {
                    context.fill(PANEL_X + 8, rowY, PANEL_X + 8 + FACE_SIZE, rowY + FACE_SIZE, 0xFF3C3C3C);
                }

                AlbaJob job = AlbaJob.fromName(entry.job());
                String jobLabel = job != null ? job.label() : "미지정";
                context.drawTextWithShadow(textRenderer,
                        Text.literal(entry.employee() + " · " + jobLabel + " · " + relativeTime(entry.hiredAtMillis()) + " 고용"),
                        PANEL_X + 8 + FACE_SIZE + 4, rowY + 2, COLOR_TITLE);

                AlbaZone zone = job == AlbaJob.PUMPKIN ? parseZone(entry.zone()) : null;
                if (zone != null) {
                    int swatchX = PANEL_X + PANEL_WIDTH - 50 - ZONE_SWATCH_SIZE - 6;
                    int swatchY = rowY + 2;
                    context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
                    drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
                }
                rowY += 18;
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
        int pvX = previewX();
        int pvY = previewY();
        if (click.x() >= pvX && click.x() < pvX + PREVIEW_W && click.y() >= pvY && click.y() < pvY + PREVIEW_H) {
            draggingPreview = true;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingPreview) {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudX = Math.max(0, settings.hudX + (int) (offsetX * PREVIEW_SCALE_DIV));
            settings.hudY = Math.max(0, settings.hudY + (int) (offsetY * PREVIEW_SCALE_DIV));
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
