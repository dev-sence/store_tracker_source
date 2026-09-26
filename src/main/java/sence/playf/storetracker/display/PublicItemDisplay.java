package sence.playf.storetracker.display;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.registry.FeatureToggles;
import sence.playf.storetracker.registry.HeldItemLedger;
import sence.playf.storetracker.tracker.ContainerSlots;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * "지금 내가 들고 있다고 인정되는" 공용템 개수(HeldItemLedger)가 1개 이상인 타입에만 툴팁을 덧씌운다.
 * 이름이 같은 개인 아이템은 보유 장부 카운트가 0이라 표시되지 않는다.
 * 실제 아이템 데이터는 건드리지 않는 순수 렌더링이라 서버/다른 플레이어에는 영향 없음.
 */
public final class PublicItemDisplay {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static final Set<String> LOGGED_KEYS = Collections.synchronizedSet(new HashSet<>());

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!FeatureToggles.publicTag()) {
                return;
            }

            String identityKey = ContainerSlots.identityKey(stack);
            boolean held = HeldItemLedger.isHeld(identityKey);

            if (LOGGED_KEYS.add(identityKey)) {
                LOGGER.info("[tooltip] key={} held={} heldCount={}",
                        identityKey, held, HeldItemLedger.heldCount(identityKey));
            }

            if (!held || lines.isEmpty()) {
                return;
            }

            Text original = lines.get(0);
            lines.set(0, Text.literal("[공용템] ").formatted(Formatting.GOLD).append(original));
            lines.add(Text.literal(ContainerSlots.baseItemId(stack)).formatted(Formatting.DARK_GRAY));
        });
    }

    private PublicItemDisplay() {
    }
}
