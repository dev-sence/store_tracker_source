package sence.playf.storetracker.display;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import sence.playf.storetracker.mixin.ScreenAddDrawableChildInvoker;

public final class ScreenButtons {
    public static void add(Screen screen, ClickableWidget widget) {
        ((ScreenAddDrawableChildInvoker) screen).sence_storetracker$addDrawableChild(widget);
    }

    private ScreenButtons() {
    }
}
