package sence.playf.storetracker.alba;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/**
 * 1.21.x 버전마다 타입/메서드명이 바뀌는 렌더링·입력 API를 여기 하나에 가둔다. 이 파일(main)은
 * 모든 버전에서 안전하게 컴파일되는 기본 구현(얼굴 아이콘 없음, HUD 크기 고정, 키바인드 없음)이고,
 * 1.21.4/1.21.11 빌드에서는 build.gradle이 이 파일 대신 richClassic/richModern 쪽 구현을 쓴다
 * (파일 이름·클래스 이름·메서드 시그니처가 전부 동일해서 호출 쪽 코드는 바뀔 필요가 없다).
 */
public final class AlbaPlatform {
    public static boolean richFeaturesAvailable() {
        return false;
    }

    /** HUD를 (pivotX, pivotY)를 중심으로 scale배 키워서 그린다. 기본 구현은 크기조절을 지원하지
     * 않으므로 그냥 그대로 그린다 - draw 안의 좌표는 항상 절대 좌표(이미 pivotX/Y가 반영된 값)를
     * 쓰므로, 크기조절이 안 먹혀도 위치 자체는 정상 동작한다. */
    public static void withHudScale(DrawContext context, int pivotX, int pivotY, float scale, Runnable draw) {
        draw.run();
    }

    public static Identifier uploadFaceTexture(TextureManager manager, String key, NativeImage image) {
        return null;
    }

    public static void drawFace(DrawContext context, Identifier texture, int x, int y, int size) {
    }

    private AlbaPlatform() {
    }
}
