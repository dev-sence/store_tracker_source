package sence.playf.storetracker.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sence.playf.storetracker.tracker.InsertGuard;

/** 등록 상자에 개인템을 넣으려는 슬롯 클릭을 서버로 보내기 전에 가로채서 막는다. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenClickGuardMixin {

    @Inject(
            method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sence_storetracker$guardInsert(Slot slot, int slotId, int button, SlotActionType actionType,
                                                 CallbackInfo ci) {
        ScreenHandler handler = ((HandledScreen<?>) (Object) this).getScreenHandler();
        if (InsertGuard.shouldBlock(handler, slot, actionType)) {
            ci.cancel();
        }
    }
}
