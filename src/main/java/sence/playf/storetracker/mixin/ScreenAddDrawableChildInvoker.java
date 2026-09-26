package sence.playf.storetracker.mixin;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Screen#addDrawableChild은 protected라 밖에서 못 불러서, "현재 아이템 캡처" 버튼을
 * 상자 화면에 추가하기 위해 Mixin invoker로 노출한다.
 */
@Mixin(Screen.class)
public interface ScreenAddDrawableChildInvoker {
    @Invoker("addDrawableChild")
    Element sence_storetracker$addDrawableChild(Element drawable);
}
