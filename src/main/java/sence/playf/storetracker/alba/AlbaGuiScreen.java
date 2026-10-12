package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 고용 직후(또는 "수정" 버튼으로) 뜨는 작업 선택 GUI. 알바 이름과 작업 버튼들을 보여준다. 기타작물을
 * 고르면 하위 작물 7개로, 호박을 고르면 구역 4개로 다시 넘어간다. 버튼(ClickableWidget)만 써서
 * Screen의 커스텀 마우스 클릭 오버라이드가 필요 없다 (1.21.9부터 그 API가 바뀌어 전 버전 호환이
 * 안 됐음 - 버튼 클릭은 엔진이 버전과 무관하게 처리해준다).
 */
public class AlbaGuiScreen extends Screen {
    private enum Stage { TOP, OTHER_CROP, ZONE }

    private static final int BUTTON_WIDTH = 110;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_PADDING = 10;
    private static final int COLOR_BG = 0xE61B1B1B;
    private static final int COLOR_BORDER = 0x66FFFFFF;
    private static final int COLOR_TITLE = 0xFFECECEC;
    private static final int ZONE_SWATCH_SIZE = 10;
    private static final int FACE_SIZE = 16;

    private final String employer;
    private final String employee;
    private Stage stage = Stage.TOP;
    private final List<ItemIconHint> iconHints = new ArrayList<>();
    private final List<ColorHint> colorHints = new ArrayList<>();

    private record ItemIconHint(ItemStack icon, int x, int y) {
    }

    private record ColorHint(int color, int x, int y) {
    }

    public static void openForNewHire(String employer, String employee) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.setScreen(new AlbaGuiScreen(employer, employee));
            }
        });
    }

    public AlbaGuiScreen(String employer, String employee) {
        super(Text.literal("알바 작업 선택"));
        this.employer = employer;
        this.employee = employee;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private int panelX() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int panelY() {
        return (height - panelHeight()) / 2;
    }

    private int panelHeight() {
        int rows = currentEntries().size() + (stage != Stage.TOP ? 1 : 0);
        return PANEL_PADDING * 2 + 16 + rows * (BUTTON_HEIGHT + BUTTON_GAP);
    }

    private record Entry(ItemStack icon, String label, Integer swatchColor, Runnable onClick) {
    }

    private List<Entry> currentEntries() {
        return switch (stage) {
            case TOP -> mapJobs(AlbaJob.topLevel());
            case OTHER_CROP -> mapJobs(AlbaJob.otherCrops());
            case ZONE -> mapZones();
        };
    }

    private List<Entry> mapJobs(AlbaJob[] jobs) {
        List<Entry> list = new ArrayList<>();
        for (AlbaJob job : jobs) {
            list.add(new Entry(new ItemStack(job.icon()), job.label(), null, () -> onJobClicked(job)));
        }
        return list;
    }

    private List<Entry> mapZones() {
        AlbaSettings settings = AlbaSettings.get();
        List<Entry> list = new ArrayList<>();
        for (AlbaZone zone : AlbaZone.values()) {
            list.add(new Entry(new ItemStack(zone.icon()), settings.zoneLabel(zone), zone.color(), () -> onZoneClicked(zone)));
        }
        return list;
    }

    private void onJobClicked(AlbaJob job) {
        if (job == AlbaJob.OTHER_CROP) {
            stage = Stage.OTHER_CROP;
            rebuild();
            return;
        }
        if (job == AlbaJob.PUMPKIN) {
            stage = Stage.ZONE;
            rebuild();
            return;
        }
        confirm(job, null);
    }

    private void onZoneClicked(AlbaZone zone) {
        confirm(AlbaJob.PUMPKIN, zone.name());
    }

    private void confirm(AlbaJob job, String zone) {
        AlbaTracker.getBridge().setJob(employer, employee, job.name(), zone);
        AlbaTracker.forcePollNow();
        close();
    }

    private void rebuild() {
        clearChildren();
        iconHints.clear();
        colorHints.clear();

        int px = panelX();
        int py = panelY();
        int rowY = py + PANEL_PADDING + 16;

        if (stage != Stage.TOP) {
            addDrawableChild(ButtonWidget.builder(Text.literal("◀ 뒤로"), b -> {
                        stage = Stage.TOP;
                        rebuild();
                    })
                    .dimensions(px + PANEL_PADDING, rowY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
            rowY += BUTTON_HEIGHT + BUTTON_GAP;
        }

        for (Entry entry : currentEntries()) {
            int buttonX = px + PANEL_PADDING + 14;
            ButtonWidget button = ButtonWidget.builder(Text.literal("  " + entry.label()), b -> entry.onClick().run())
                    .dimensions(buttonX, rowY, BUTTON_WIDTH + 40, BUTTON_HEIGHT).build();
            addDrawableChild(button);
            iconHints.add(new ItemIconHint(entry.icon(), px + PANEL_PADDING + 2, rowY + 2));
            if (entry.swatchColor() != null) {
                int swatchX = buttonX + BUTTON_WIDTH + 40 - ZONE_SWATCH_SIZE - 6;
                int swatchY = rowY + (BUTTON_HEIGHT - ZONE_SWATCH_SIZE) / 2;
                colorHints.add(new ColorHint(entry.swatchColor(), swatchX, swatchY));
            }
            rowY += BUTTON_HEIGHT + BUTTON_GAP;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int px = panelX();
        int py = panelY();
        int height = panelHeight();
        context.fill(px, py, px + PANEL_WIDTH, py + height, COLOR_BG);
        drawBorder(context, px, py, PANEL_WIDTH, height, COLOR_BORDER);

        String title = stage == Stage.TOP ? employee + " 작업 선택"
                : stage == Stage.OTHER_CROP ? "기타작물 선택" : "작업 구역 선택";
        int titleX = px + PANEL_PADDING;
        if (AlbaPlatform.richFeaturesAvailable()) {
            Identifier face = PlayerFaceFetcher.getFaceTexture(employee);
            int faceY = py + PANEL_PADDING - 2;
            if (face != null) {
                AlbaPlatform.drawFace(context, face, titleX, faceY, FACE_SIZE);
            } else {
                context.fill(titleX, faceY, titleX + FACE_SIZE, faceY + FACE_SIZE, 0xFF3C3C3C);
            }
            titleX += FACE_SIZE + 6;
        }
        context.drawTextWithShadow(textRenderer, AlbaPlatform.styledText(title, true, COLOR_TITLE), titleX, py + PANEL_PADDING, COLOR_TITLE);

        super.render(context, mouseX, mouseY, delta);

        for (ItemIconHint hint : iconHints) {
            context.drawItem(hint.icon(), hint.x(), hint.y());
        }
        for (ColorHint hint : colorHints) {
            context.fill(hint.x(), hint.y(), hint.x() + ZONE_SWATCH_SIZE, hint.y() + ZONE_SWATCH_SIZE, hint.color());
            drawBorder(context, hint.x(), hint.y(), ZONE_SWATCH_SIZE, ZONE_SWATCH_SIZE, COLOR_BORDER);
        }
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
