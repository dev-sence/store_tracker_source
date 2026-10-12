package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;

/** 1.21.4 전용 - playf의 FarmHudScreen과 같은 방식: 진짜 HUD 패널을 실제 화면 위치에 그대로
 * 그려서, 그 패널 자체를 드래그하면 바로 hudX/hudY가 바뀐다(설정창 안의 축소 미리보기가 아님).
 * 조작용 패널(표시 토글/크기조절/알바별 수정)은 화면 하단에 따로 둔다. "/알바설정" 또는
 * 키바인드로 연다. */
public class AlbaSettingsScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int ROW_H = 24;
    private static final int COLOR_BG = 0xF01B1B1B;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF6FB0E8;
    private static final int FACE_SIZE = 16;
    private static final int ZONE_SWATCH_SIZE = 10;
    private static final int DIM_COLOR = 0x80000000;

    private ButtonWidget visibleToggleButton;
    private boolean draggingHud = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private int dragStartHudX;
    private int dragStartHudY;

    public AlbaSettingsScreen() {
        super(Text.literal("알바 설정"));
    }

    private int panelX() {
        return 20;
    }

    private int panelY() {
        return height - panelHeight() - 20;
    }

    @Override
    protected void init() {
        AlbaTracker.pollNow();
        int px = panelX();
        int py = panelY();

        visibleToggleButton = ButtonWidget.builder(Text.literal(""), b -> {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudVisible = !settings.hudVisible;
            settings.save();
        }).dimensions(px + PANEL_WIDTH - 130, py + 40, 110, 22).build();
        addDrawableChild(visibleToggleButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> AlbaSettings.get().adjustScale(-10))
                .dimensions(px + PANEL_WIDTH - 130, py + 74, 24, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> AlbaSettings.get().adjustScale(10))
                .dimensions(px + PANEL_WIDTH - 102, py + 74, 24, 22).build());

        int rowY = py + 108;
        for (AlbaEntry entry : myHires()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("수정"), b ->
                            client.setScreen(new AlbaGuiScreen(entry.employer(), entry.employee())))
                    .dimensions(px + PANEL_WIDTH - 60, rowY, 48, 20).build());
            rowY += ROW_H;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), b -> close())
                .dimensions(px + PANEL_WIDTH - 64, py + panelHeight() - 32, 52, 22).build());
    }

    private List<AlbaEntry> myHires() {
        MinecraftClient client = MinecraftClient.getInstance();
        String me = client.player != null ? client.player.getName().getString() : "";
        return AlbaTracker.getCachedHires().stream()
                .filter(e -> e.employer().equalsIgnoreCase(me))
                .toList();
    }

    private int panelHeight() {
        return 108 + Math.max(1, myHires().size()) * ROW_H + 40;
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
        int ph = panelHeight();
        context.fill(px, py, px + PANEL_WIDTH, py + ph, COLOR_BG);
        drawBorder(context, px, py, PANEL_WIDTH, ph, COLOR_BORDER);

        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("알바 설정", true, COLOR_TITLE), px + 12, py + 12, COLOR_TITLE);
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("위 패널을 그대로 드래그해서 옮기세요", false, COLOR_ACCENT),
                px + 12, py + 26, COLOR_ACCENT);

        visibleToggleButton.setMessage(Text.literal("표시: " + (settings.hudVisible ? "ON" : "OFF")));
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("알바 현황 표시", false, COLOR_MUTED), px + 12, py + 46, COLOR_MUTED);
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText("HUD 크기: " + settings.scalePercent + "%", false, COLOR_MUTED),
                px + 12, py + 80, COLOR_MUTED);

        List<AlbaEntry> mine = myHires();
        int rowY = py + 108;
        if (mine.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("지금 고용 중인 알바가 없어요").formatted(Formatting.GRAY),
                    px + 12, rowY + 4, COLOR_MUTED);
        } else {
            for (AlbaEntry entry : mine) {
                Identifier face = PlayerFaceFetcher.getFaceTexture(entry.employee());
                if (face != null) {
                    AlbaPlatform.drawFace(context, face, px + 12, rowY + 2, FACE_SIZE);
                } else {
                    context.fill(px + 12, rowY + 2, px + 12 + FACE_SIZE, rowY + 2 + FACE_SIZE, 0xFF3C3C3C);
                }

                AlbaJob job = AlbaJob.fromName(entry.job());
                String jobLabel = job != null ? job.label() : "미지정";
                context.drawTextWithShadow(textRenderer,
                        AlbaPlatform.styledText(entry.employee() + " · " + jobLabel + " · " + relativeTime(entry.hiredAtMillis()) + " 고용", false, COLOR_TITLE),
                        px + 12 + FACE_SIZE + 6, rowY + 6, COLOR_TITLE);

                AlbaZone zone = job == AlbaJob.PUMPKIN ? parseZone(entry.zone()) : null;
                if (zone != null) {
                    int swatchX = px + PANEL_WIDTH - 60 - ZONE_SWATCH_SIZE - 8;
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int[] rect = hudScreenRect();
        if (mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
            draggingHud = true;
            dragStartMouseX = (int) mouseX;
            dragStartMouseY = (int) mouseY;
            AlbaSettings settings = AlbaSettings.get();
            dragStartHudX = settings.hudX;
            dragStartHudY = settings.hudY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingHud) {
            AlbaSettings settings = AlbaSettings.get();
            settings.hudX = Math.max(0, dragStartHudX + (int) Math.round(mouseX - dragStartMouseX));
            settings.hudY = Math.max(0, dragStartHudY + (int) Math.round(mouseY - dragStartMouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingHud) {
            draggingHud = false;
            AlbaSettings.get().save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
