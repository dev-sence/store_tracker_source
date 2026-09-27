package sence.playf.storetracker.tracker;

import net.minecraft.component.DataComponentTypes;
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
     * 서로 다른 아이템을 구분할 수 없다. 그래서 커스텀 이름이 있는 아이템만 "바닐라ID#표시이름"을 쓴다.
     *
     * 커스텀 이름이 없는 평범한 바닐라 아이템(예: 물 양동이)은 절대 표시이름을 키에 넣으면 안 된다 -
     * {@code stack.getName()}은 그 이름이 없을 때 "지금 이 클라이언트의 언어 설정"으로 번역된 이름을
     * 돌려주기 때문에, 영어 클라이언트는 "Water Bucket", 한국어 클라이언트는 "물 양동이"로 서로
     * 다르게 읽어서 실제로는 같은 아이템인데 서로 다른 것으로 갈려 재고가 두 갈래로 쪼개지는
     * 버그가 있었다.
     */
    public static String identityKey(ItemStack stack) {
        if (stack.get(DataComponentTypes.CUSTOM_NAME) != null) {
            return baseItemId(stack) + "#" + stack.getName().getString();
        }
        return baseItemId(stack);
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
