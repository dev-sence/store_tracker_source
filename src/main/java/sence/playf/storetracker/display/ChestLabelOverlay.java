package sence.playf.storetracker.display;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 등록된 공용템 상자의 실제 제목(Screen#title)은 건드리지 않고, 그 위에 강조 라벨만 따로 그린다.
 * (title을 직접 바꾸면 이 서버의 커스텀 GUI 배경 렌더링이 title 값에 의존하고 있어서 깨지는 문제가 있었음)
 */
public final class ChestLabelOverlay {
    public static void draw(DrawContext context, Screen screen, String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        Text text = Text.literal("◆ " + label + " ◆").formatted(Formatting.GOLD, Formatting.BOLD);
        context.drawCenteredTextWithShadow(client.textRenderer, text, screen.width / 2, 6, 0xFFFFFF);
    }

    private ChestLabelOverlay() {
    }
}
