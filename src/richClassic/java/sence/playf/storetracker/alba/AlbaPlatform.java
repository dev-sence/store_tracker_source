package sence.playf.storetracker.alba;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
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
        // width/height(그릴 크기)와 regionWidth/regionHeight(텍스처에서 뗄 크기)를 따로 안 주는
        // 10-인자 오버로드를 썼더니, 8x8 텍스처를 size(예: 14)만큼 그릴 때 UV가 0~size로 계산돼
        // (U가 텍스처 폭을 넘어가면 기본적으로 반복/REPEAT 샘플링됨) 얼굴이 2x2로 타일링되어
        // "머리 4개가 붙어있는" 것처럼 보였다. region을 텍스처 실제 크기(8,8)로 명시하는
        // 12-인자 오버로드로 바꿔서 고침(그릴 크기는 size로 늘리고, 샘플은 8x8 한 장만).
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 0f, 0f, size, size, 8, 8, 8, 8);
    }

    /** playf에서 쓰던 NEXON 폰트(bold/medium)를 적용한 Text - 디자인을 최대한 비슷하게 맞추려고
     * 포팅함. argbColor는 알파를 무시하고 RGB만 쓴다(음영은 drawTextWithShadow가 알아서 그림). */
    public static Text styledText(String text, boolean bold, int argbColor) {
        Identifier font = Identifier.of("sence_storetracker", bold ? "nexon_bold" : "nexon_medium");
        return Text.literal(text).setStyle(Style.EMPTY.withFont(font).withColor(TextColor.fromRgb(argbColor & 0xFFFFFF)));
    }

    private AlbaPlatform() {
    }
}
