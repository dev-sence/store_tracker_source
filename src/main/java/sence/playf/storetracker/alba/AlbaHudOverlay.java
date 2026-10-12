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
    private static final int ROW_HEIGHT = LINE_HEIGHT * 2 + 2;
    private static final int PADDING = 7;
    private static final int TITLE_GAP = 4;
    private static final int JOB_ICON_SIZE = 14;
    private static final int ZONE_SWATCH_SIZE = 8;
    private static final int FACE_SIZE = 14;
    private static final int FACE_GAP = 4;

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
        if (client.player == null || client.currentScreen != null || client.options.hudHidden) {
            return;
        }
        int ox = settings.hudX;
        int oy = settings.hudY;
        float scale = settings.scalePercent / 100f;

        AlbaPlatform.withHudScale(context, ox, oy, scale, () -> draw(context, client.textRenderer, ox, oy));
    }

    /** 지금 패널을 실제로 그리면 가로/세로가 몇 픽셀일지 - 설정화면이 미리보기의 드래그 가능
     * 영역을 정확히 알아야 해서 공개한다. */
    static int[] measure(TextRenderer tr) {
        List<AlbaEntry> entries = AlbaTracker.getCachedHires();
        boolean faces = AlbaPlatform.richFeaturesAvailable();
        int textXOffset = faces ? FACE_SIZE + FACE_GAP : 0;
        int shown = Math.min(entries.size(), MAX_ROWS);
        int width = textXOffset + tr.getWidth("알바 현황") + PADDING * 2;
        int rowCount = entries.isEmpty() ? 1 : shown;
        int height = PADDING * 2 + LINE_HEIGHT + TITLE_GAP + rowCount * ROW_HEIGHT + (entries.size() > MAX_ROWS ? LINE_HEIGHT : 0);
        for (AlbaEntry e : entries.subList(0, shown)) {
            String title = e.employer() + " → " + e.employee();
            String detail = detailText(e);
            int rowWidth = PADDING * 2 + textXOffset + Math.max(tr.getWidth(title), tr.getWidth(detail))
                    + JOB_ICON_SIZE + ZONE_SWATCH_SIZE + 18;
            width = Math.max(width, rowWidth);
        }
        return new int[]{width, height};
    }

    /** 패키지 내 공개 - 알바 설정화면이 "지금 실제로 보이는 모습 그대로" 미리보기를 그릴 때도 쓴다
     * (더 이상 빈 네모가 아니라 진짜 내용을 그려서, 그걸 직접 드래그하는 느낌이 나게). */
    static void draw(DrawContext context, TextRenderer tr, int ox, int oy) {
        List<AlbaEntry> entries = AlbaTracker.getCachedHires();
        boolean faces = AlbaPlatform.richFeaturesAvailable();
        int textXOffset = faces ? FACE_SIZE + FACE_GAP : 0;

        int[] size = measure(tr);
        int width = size[0];
        int height = size[1];
        int shown = Math.min(entries.size(), MAX_ROWS);

        context.fill(ox, oy, ox + width, oy + height, COLOR_BG);
        drawBorder(context, ox, oy, width, height, COLOR_BORDER);

        int y = oy + PADDING;
        context.drawTextWithShadow(tr, AlbaPlatform.styledText("알바 현황", true, COLOR_TITLE), ox + PADDING, y, COLOR_TITLE);
        y += LINE_HEIGHT + TITLE_GAP;

        if (entries.isEmpty()) {
            context.drawTextWithShadow(tr, AlbaPlatform.styledText("고용된 알바가 없어요", false, COLOR_MUTED), ox + PADDING, y, COLOR_MUTED);
        } else {
            for (AlbaEntry e : entries.subList(0, shown)) {
                int rowStartY = y;
                if (faces) {
                    int faceY = rowStartY + (ROW_HEIGHT - FACE_SIZE) / 2;
                    drawFace(context, e.employee(), ox + PADDING, faceY);
                }

                int textX = ox + PADDING + textXOffset;
                String title = e.employer() + " → " + e.employee();
                context.drawTextWithShadow(tr, AlbaPlatform.styledText(title, true, COLOR_TITLE), textX, y, COLOR_TITLE);

                AlbaJob job = AlbaJob.fromName(e.job());
                int iconX = ox + width - PADDING - JOB_ICON_SIZE;
                if (job != null) {
                    int iconY = rowStartY + (ROW_HEIGHT - JOB_ICON_SIZE) / 2;
                    context.drawItem(new ItemStack(job.icon()), iconX, iconY);
                    AlbaZone zone = parseZone(e.zone());
                    if (zone != null) {
                        int swatchX = iconX - ZONE_SWATCH_SIZE - 4;
                        int swatchY = rowStartY + (ROW_HEIGHT - ZONE_SWATCH_SIZE) / 2;
                        context.fill(swatchX, swatchY, swatchX + ZONE_SWATCH_SIZE, swatchY + ZONE_SWATCH_SIZE, zone.color());
                        drawBorder(context, swatchX, swatchY, ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
                    }
                }

                context.drawTextWithShadow(tr, AlbaPlatform.styledText(detailText(e), false, COLOR_MUTED), textX, y + LINE_HEIGHT, COLOR_MUTED);
                y += ROW_HEIGHT;
            }
            if (entries.size() > MAX_ROWS) {
                context.drawTextWithShadow(tr, AlbaPlatform.styledText("+ " + (entries.size() - MAX_ROWS) + "개 더", false, COLOR_ACCENT),
                        ox + PADDING, y, COLOR_ACCENT);
            }
        }
    }

    /** measure()와 draw()가 서로 다른 문자열로 폭을 계산/그리면 테두리보다 글자가 삐져나가는
     * 문제가 생겨서("HUD 박스가 안 맞음"), 폭 계산과 실제 그리기가 항상 똑같은 문자열을 쓰도록
     * 여기 하나로 모았다. */
    private static String detailText(AlbaEntry e) {
        AlbaJob job = AlbaJob.fromName(e.job());
        return (job != null ? job.label() + " · " : "") + relativeTime(e.hiredAtMillis()) + " 고용";
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
