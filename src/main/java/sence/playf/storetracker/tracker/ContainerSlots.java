package sence.playf.storetracker.tracker;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 컨테이너(상자류) 스크린 핸들러의 "자기 자신 슬롯" 개수/내용을 다루는 공용 헬퍼. */
public final class ContainerSlots {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    // 바닐라 상자/배럴/셔틀박스뿐 아니라, 서버 플러그인이 구현하는 커스텀 상자류 GUI까지
    // 전부 잡으려고 특정 클래스를 나열하는 대신 관례를 이용한다: 컨테이너류 화면은 항상
    // 자기 슬롯 뒤에 "플레이어 인벤토리 27칸 + 단축바 9칸" = 36칸이 그대로 붙어 나온다
    // (바닐라 GenericContainer/ShulkerBox는 물론, 서버 플러그인이 만드는 거의 모든 메뉴도
    // 이 관례를 따른다). 예전엔 GenericContainerScreenHandler/ShulkerBoxScreenHandler
    // 두 클래스만 알아봐서, 서버가 다른 방식으로 상자 GUI를 구현하면(커스텀 플러그인 메뉴 등)
    // 여기서 조용히 null을 반환해 추적이 통째로 멈추는 문제가 있었다.
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    // 저장 용도가 아닌 특수 화면(제작대/모루/마법부여대 등)은 슬롯 개수 공식만으로는 상자류와
    // 구분이 안 되니 명시적으로 제외한다 - 안 그러면 이런 화면에서 손에 들고 있던 공용템을
    // 임시로 슬롯에 올렸다 내리는 것만으로도 가짜 입출고로 잡힐 수 있다.
    private static final Set<Class<?>> NON_CONTAINER_HANDLERS = Set.of(
            PlayerScreenHandler.class, CraftingScreenHandler.class, AnvilScreenHandler.class,
            EnchantmentScreenHandler.class, BeaconScreenHandler.class, LoomScreenHandler.class,
            StonecutterScreenHandler.class, GrindstoneScreenHandler.class, SmithingScreenHandler.class,
            CartographyTableScreenHandler.class, MerchantScreenHandler.class
    );

    /** 이 핸들러가 추적 가능한 컨테이너류(자기 슬롯 + 플레이어 인벤토리 36칸 구조)이면
     * 자기 슬롯 개수를, 아니면(저장 용도가 아닌 특수 화면) null을 반환한다. */
    public static Integer slotCountFor(ScreenHandler handler) {
        if (NON_CONTAINER_HANDLERS.contains(handler.getClass())) {
            return null;
        }
        int total = handler.slots.size();
        int containerSlots = total - PLAYER_INVENTORY_SLOTS;
        if (containerSlots <= 0) {
            LOGGER.info("컨테이너로 인식되지 않는 화면 (totalSlots={}, class={})", total, handler.getClass().getName());
            return null;
        }
        return containerSlots;
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
