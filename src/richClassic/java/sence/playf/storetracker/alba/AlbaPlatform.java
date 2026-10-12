package sence.playf.storetracker.alba;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/** 1.21.4 전용 구현 - MatrixStack(push/translate/scale 3-arg)와 RenderLayer::getGuiTextured를 쓴다.
 * build.gradle이 이 파일을 main의 AlbaPlatform.java 대신 컴파일한다 (1.21.4 빌드에서만). */
public final class AlbaPlatform {
    public static boolean richFeaturesAvailable() {
        return true;
    }

    public static void withHudScale(DrawContext context, int pivotX, int pivotY, float scale, Runnable draw) {
        context.getMatrices().push();
        context.getMatrices().translate(pivotX, pivotY, 0f);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-pivotX, -pivotY, 0f);
        draw.run();
        context.getMatrices().pop();
    }

    /** image는 64x64(이상) 스킨 원본 - 여기서 8x8 얼굴+모자 레이어만 잘라서 텍스처로 등록한다. */
    public static Identifier uploadFaceTexture(TextureManager manager, String key, NativeImage image) {
        NativeImage face = new NativeImage(NativeImage.Format.RGBA, 8, 8, false);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                face.setColorArgb(x, y, image.getColorArgb(8 + x, 8 + y));
                int overlayArgb = image.getColorArgb(40 + x, 8 + y);
                if (overlayArgb >>> 24 != 0) {
                    face.setColorArgb(x, y, overlayArgb);
                }
            }
        }
        NativeImageBackedTexture texture = new NativeImageBackedTexture(face);
        Identifier id = Identifier.of("sence_storetracker", "alba_face_" + key);
        manager.registerTexture(id, texture);
        return id;
    }

    public static void drawFace(DrawContext context, Identifier texture, int x, int y, int size) {
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 0f, 0f, size, size, 8, 8);
    }

    private AlbaPlatform() {
    }
}
