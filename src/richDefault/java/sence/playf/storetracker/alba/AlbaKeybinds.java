package sence.playf.storetracker.alba;

import net.minecraft.client.MinecraftClient;

/** 기본(main) 구현 - 아무 것도 안 한다. 1.21.4/1.21.11 빌드에서는 KeyBinding.Category 타입이
 * 서로 달라서 richClassic/richModern 쪽의 진짜 구현으로 통째로 바뀐다. 다른 버전은 "/알바설정"
 * 명령어로 설정을 연다. */
public final class AlbaKeybinds {
    public static void register() {
    }

    public static void tick(MinecraftClient client) {
    }

    private AlbaKeybinds() {
    }
}
