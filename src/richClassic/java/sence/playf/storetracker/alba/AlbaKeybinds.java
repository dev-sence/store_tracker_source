package sence.playf.storetracker.alba;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** 1.21.4 전용 - KeyBinding 생성자의 카테고리 인자가 문자열(번역 키)이다. */
public final class AlbaKeybinds {
    private static KeyBinding openSettingsKey;

    public static void register() {
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sence_storetracker.alba_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.sence_storetracker"));
    }

    public static void tick(MinecraftClient client) {
        while (openSettingsKey.wasPressed()) {
            if (client.currentScreen == null) {
                client.setScreen(new AlbaSettingsScreen());
            }
        }
    }

    private AlbaKeybinds() {
    }
}
