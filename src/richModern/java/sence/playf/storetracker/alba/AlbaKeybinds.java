package sence.playf.storetracker.alba;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** 1.21.11 전용 - KeyBinding 생성자의 카테고리 인자가 KeyBinding.Category 전용 타입으로 바뀌었다. */
public final class AlbaKeybinds {
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("sence_storetracker", "main"));

    private static KeyBinding openSettingsKey;

    public static void register() {
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sence_storetracker.alba_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY));
    }

    public static void tick(MinecraftClient client) {
        while (openSettingsKey.wasPressed()) {
            if (client.currentScreen == null) {
                client.setScreen(new AlbaEditScreen());
            }
        }
    }

    private AlbaKeybinds() {
    }
}
