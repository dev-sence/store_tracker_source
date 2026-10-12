package sence.playf.storetracker.alba;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

/** 1.21.11 전용 구현 - Matrix3x2fStack(pushMatrix/popMatrix)과 RenderPipelines.GUI_TEXTURED를 쓴다
 * (1.21.6+ 렌더 파이프라인 개편으로 RenderLayer 기반 텍스처 그리기가 없어짐). build.gradle이 이
 * 파일을 main의 AlbaPlatform.java 대신 컴파일한다 (1.21.11 빌드에서만). */
public final class AlbaPlatform {
    public static boolean richFeaturesAvailable() {
        return true;
    }

    public static void withHudScale(DrawContext context, int pivotX, int pivotY, float scale, Runnable draw) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) pivotX, (float) pivotY);
        context.getMatrices().scale(scale);
        context.getMatrices().translate((float) -pivotX, (float) -pivotY);
        draw.run();
        context.getMatrices().popMatrix();
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
        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "storetracker alba face " + key, face);
        Identifier id = Identifier.of("sence_storetracker", "alba_face_" + key);
        manager.registerTexture(id, texture);
        return id;
    }

    public static void drawFace(DrawContext context, Identifier texture, int x, int y, int size) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, 8, 8);
    }

    /** playf에서 쓰던 NEXON 폰트(bold/medium)를 적용한 Text - 디자인을 최대한 비슷하게 맞추려고
     * 포팅함. argbColor는 알파를 무시하고 RGB만 쓴다(음영은 drawTextWithShadow가 알아서 그림). */
    public static Text styledText(String text, boolean bold, int argbColor) {
        Identifier font = Identifier.of("sence_storetracker", bold ? "nexon_bold" : "nexon_medium");
        return Text.literal(text).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(font)).withColor(TextColor.fromRgb(argbColor & 0xFFFFFF)));
    }

    private AlbaPlatform() {
    }
}
