package sence.playf.storetracker.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sence.playf.storetracker.tracker.ContainerTracker;
import sence.playf.storetracker.tracker.InsertGuard;

/**
 * 등록 상자에 개인템을 넣으려는 슬롯 클릭을 서버로 보내기 전에 가로채서 막고(HEAD),
 * 막히지 않은 클릭은 처리 전/후로 상자 슬롯을 스냅샷해서 이번 클릭 한 번이 만든 변화만
 * 입출고로 기록한다(HEAD 이후 / RETURN). 클릭 단위로 좁혀서, 같은 상자를 동시에 보고 있는
 * 다른 플레이어의 변화가 내 것으로 잘못 잡히는 걸 막는다.
 */
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
            return;
        }
        ContainerTracker.beforeClick(slot, actionType);
    }

    @Inject(
            method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("RETURN")
    )
    private void sence_storetracker$trackClick(Slot slot, int slotId, int button, SlotActionType actionType,
                                                CallbackInfo ci) {
        ContainerTracker.afterClick(slot, actionType);
    }
}
