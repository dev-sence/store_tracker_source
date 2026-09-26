package sence.playf.storetracker.tracker;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;

import java.util.HashMap;
import java.util.Map;

/** 컨테이너(상자류) 스크린 핸들러의 "자기 자신 슬롯" 개수/내용을 다루는 공용 헬퍼. */
public final class ContainerSlots {

    /** 이 핸들러가 추적 가능한 컨테이너(상자/배럴/셔틀박스)이면 자기 슬롯 개수를, 아니면 null을 반환한다. */
    public static Integer slotCountFor(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) {
            return generic.getRows() * 9;
        }
        if (handler instanceof ShulkerBoxScreenHandler) {
            return 27;
        }
        return null;
    }

    /**
     * 이 서버는 바닐라 아이템 종류 하나(예: netherite_axe)를 재사용해서 커스텀 이름이 다른
     * 여러 아이템(예: "[토르] 원콕 도끼" vs "[이터널] 원콕 도끼")을 만들기 때문에, 바닐라 ID만으로는
     * 서로 다른 아이템을 구분할 수 없다. 그래서 "바닐라ID#표시이름"을 아이템 식별자로 쓴다.
     */
    public static String identityKey(ItemStack stack) {
        return baseItemId(stack) + "#" + stack.getName().getString();
    }

    public static String baseItemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    public static Map<String, Integer> snapshotCounts(ScreenHandler handler, int slotCount) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(identityKey(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    public static Map<String, String> snapshotNames(ScreenHandler handler, int slotCount) {
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            names.putIfAbsent(identityKey(stack), stack.getName().getString());
        }
        return names;
    }

    private ContainerSlots() {
    }
}
