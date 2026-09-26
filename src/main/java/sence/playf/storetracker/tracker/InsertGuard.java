package sence.playf.storetracker.tracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.registry.ChestRegistry;
import sence.playf.storetracker.registry.HeldItemLedger;

/**
 * 배포용 빌드에서, "개인템 차단"이 켜진 등록 상자에 공용템이 아닌 아이템을 넣으려는 클릭을 막는다.
 * 개발자 빌드는 항상 통과 (캡처/설정 작업을 자유롭게 하기 위함).
 *
 * 스택 안에 공용템+개인템이 섞여 있으면(보유 장부 개수보다 스택 개수가 많으면) 전체를 막고
 * 정확한 개수를 알려준다 - 자동으로 일부만 쪼개 넣지는 않는다 (클라이언트에서 직접 아이템을 쪼개면
 * 서버 모르게 조작하는 셈이라 복제/소실 버그로 이어질 위험이 있어서 일부러 안전하게 막기만 한다).
 *
 * 커서에 들린 아이템이 "원래 이 상자 안에서 집어든 것"인지 "내 인벤토리에서 가져온 것"인지 구분해서,
 * 상자 안에서 공용템끼리 자리를 옮기는 건 항상 허용한다 (그건 상자 밖으로 나가는 게 아니므로).
 */
public final class InsertGuard {
    /** 지금 커서에 들린 아이템이 이 상자 안의 슬롯에서 집어든 것인지. */
    private static boolean cursorFromContainer = false;

    public static boolean shouldBlock(ScreenHandler handler, Slot slot, SlotActionType action) {
        if (BuildInfo.isDevBuild() || slot == null) {
            return false;
        }
        if (handler != ContainerTracker.activeHandler()) {
            return false;
        }
        ChestRegistry.RegisteredChest chest = ContainerTracker.activeChest();
        Integer slotCount = ContainerTracker.activeSlotCount();
        if (chest == null || !chest.strictMode() || slotCount == null) {
            return false;
        }

        boolean targetIsContainerSlot = slot.id < slotCount;

        if (action == SlotActionType.PICKUP) {
            boolean cursorWasEmpty = handler.getCursorStack().isEmpty();
            boolean blocked = !cursorWasEmpty && targetIsContainerSlot && !cursorFromContainer
                    && checkStack(handler.getCursorStack());
            if (!blocked) {
                // 이 클릭이 끝나면(뭔가 커서에 남아있다면) 그 출처는 방금 클릭한 슬롯이 된다.
                cursorFromContainer = targetIsContainerSlot;
            }
            return blocked;
        }

        if (action == SlotActionType.QUICK_MOVE) {
            // shift-클릭으로 내 인벤토리 칸의 아이템을 상자로 보내는 경우만 해당 (상자 -> 밖은 항상 허용).
            ItemStack stack = slot.getStack();
            return !targetIsContainerSlot && !stack.isEmpty() && checkStack(stack);
        }

        if (action == SlotActionType.QUICK_CRAFT) {
            // 드래그로 여러 칸에 나눠 넣기. 드래그 중엔 커서에 원래 스택 전체가 그대로 들려있다.
            return targetIsContainerSlot && !cursorFromContainer && checkStack(handler.getCursorStack());
        }

        if (action == SlotActionType.SWAP && targetIsContainerSlot) {
            // 숫자키 교환은 어떤 아이템이 들어올지 확인하기 번거로워서 컨테이너 슬롯 대상이면 일괄 차단한다.
            notify("§c숫자키 교환으로는 이 상자에 넣을 수 없습니다. 직접 클릭해서 넣어주세요.");
            return true;
        }

        return false;
    }

    /** 비어있지 않고 검사가 필요할 때만 호출됨. true를 반환하면 이미 안내 메시지도 보낸 상태다. */
    private static boolean checkStack(ItemStack stack) {
        int heldBudget = HeldItemLedger.heldCount(ContainerSlots.identityKey(stack));
        if (stack.getCount() <= heldBudget) {
            return false;
        }
        if (heldBudget <= 0) {
            notify("§c개인 아이템은 이 상자에 넣을 수 없습니다.");
        } else {
            notify("§c공용템 " + heldBudget + "개 / 개인템 " + (stack.getCount() - heldBudget)
                    + "개가 섞여 있습니다. 직접 나눠서 공용템 " + heldBudget + "개만 넣어주세요.");
        }
        return true;
    }

    private static void notify(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }

    private InsertGuard() {
    }
}
