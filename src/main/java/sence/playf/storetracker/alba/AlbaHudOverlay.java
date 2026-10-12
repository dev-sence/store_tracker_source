package sence.playf.storetracker.alba;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/** 화면 한쪽에 "지금 일하는 알바" 목록을 작게 띄운다. 위치/표시여부/크기는 {@link AlbaSettings}를
 * 따른다. 얼굴 아이콘·크기조절은 {@link AlbaPlatform}을 거친다 - 1.21.4/1.21.11 둘은 진짜로 동작하고,
 * 나머지 10개 버전은 자동으로 빠진다(행렬/텍스처 API가 버전마다 달라서 전부 지원은 못 함). */
public final class AlbaHudOverlay {
    private static final int MAX_ROWS = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int ROW_HEIGHT = LINE_HEIGHT * 2;
    private static final int PADDING = 5;
    private static final int JOB_ICON_SIZE = 10;
    private static final int ZONE_SWATCH_SIZE = 6;
    private static final int FACE_SIZE = 10;
    private static final int FACE_GAP = 2;

    private static final int COLOR_BG = 0xCC1B1B1B;
    private static final int COLOR_BORDER = 0x66FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int COLOR_ACCENT = 0xFF6FB0E8;

    private AlbaHudOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(AlbaHudOverlay::render);
    }

    private static void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        AlbaSettings settings = AlbaSettings.get();
        if (!settings.hudVisible) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null) {
            return;
        }
        int ox = settings.hudX;
        int oy = settings.hudY;
        float scale = settings.scalePercent / 100f;

        AlbaPlatform.withHudScale(context, ox, oy, scale, () -> draw(context, client.textRenderer, ox, oy));
    }

    private static void draw(DrawContext context, TextRenderer tr, int ox, int oy) {
        List<AlbaEntry> entries = AlbaTracker.getCachedHires();
        boolean faces = AlbaPlatform.richFeaturesAvailable();
        int textXOffset = faces ? FACE_SIZE * 2 + FACE_GAP + 4 : 0;

        int shown = Math.min(entries.size(), MAX_ROWS);
        int width = textXOffset + tr.getWidth("알바 현황") + PADDING * 2;
        int rowCount = entries.isEmpty() ? 1 : shown;
        int height = PADDING * 2 + LINE_HEIGHT + rowCount * ROW_HEIGHT + (entries.size() > MAX_ROWS ? LINE_HEIGHT : 0);

        for (AlbaEntry e : entries.subList(0, shown)) {
            int rowWidth = PADDING * 2 + textXOffset + tr.getWidth(e.employer() + " → " + e.employee())
                    + JOB_ICON_SIZE + ZONE_SWATCH_SIZE + 8;
            width = Math.max(width, rowWidth);
        }

        context.fill(ox, oy, ox + width, oy + height, COLOR_BG);
        drawBorder(context, ox, oy, width, height, COLOR_BORDER);

        int y = oy + PADDING;
        context.drawTextWithShadow(tr, Text.literal("알바 현황"), ox + PADDING, y, COLOR_TITLE);
        y += LINE_HEIGHT;

        if (entries.isEmpty()) {
            context.drawTextWithShadow(tr, Text.literal("고용된 알바가 없어요"), ox + PADDING, y, COLOR_MUTED);
        } else {
            for (AlbaEntry e : entries.subList(0, shown)) {
                if (faces) {
                    int faceY = y + (LINE_HEIGHT - FACE_SIZE) / 2;
                    drawFace(context, e.employer(), ox + PADDING, faceY);
                    drawFace(context, e.employee(), ox + PADDING + FACE_SIZE + FACE_GAP, faceY);
                }

                int textX = ox + PADDING + textXOffset;
                context.drawTextWithShadow(tr, Text.literal(e.employer() + " → " + e.employee()), textX, y, COLOR_TITLE);

                AlbaJob job = AlbaJob.fromName(e.job());
                int iconX = ox + width - PADDING - JOB_ICON_SIZE;
                if (job != null) {
                    context.drawItem(new ItemStack(job.icon()), iconX, y - 1);
                    AlbaZone zone = parseZone(e.zone());
                    if (zone != null) {
                        int swatchX = iconX - ZONE_SWATCH_SIZE - 3;
                        int swatchY = y + (LINE_HEIGHT - ZONE_SWATCH_SIZE) / 2;
                        context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
                        drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
                    }
                }

                String detail = (job != null ? job.label() + " · " : "") + relativeTime(e.hiredAtMillis()) + " 고용";
                context.drawTextWithShadow(tr, Text.literal(detail), textX, y + LINE_HEIGHT, COLOR_MUTED);
                y += ROW_HEIGHT;
            }
            if (entries.size() > MAX_ROWS) {
                context.drawTextWithShadow(tr, Text.literal("+ " + (entries.size() - MAX_ROWS) + "개 더"),
                        ox + PADDING, y, COLOR_ACCENT);
            }
        }
    }

    private static void drawFace(DrawContext context, String playerName, int x, int y) {
        Identifier texture = PlayerFaceFetcher.getFaceTexture(playerName);
        if (texture == null) {
            context.fill(x, y, x + FACE_SIZE, y + FACE_SIZE, 0xFF3C3C3C);
            return;
        }
        AlbaPlatform.drawFace(context, texture, x, y, FACE_SIZE);
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
}
