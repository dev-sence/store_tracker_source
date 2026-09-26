package sence.playf.storetracker.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.auth.MemberGate;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.LocalLogger;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.display.ChestLabelOverlay;
import sence.playf.storetracker.display.SaveItemsAction;
import sence.playf.storetracker.display.ScreenButtons;
import sence.playf.storetracker.display.ToggleStrictAction;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.registry.ChestRegistry;
import sence.playf.storetracker.registry.FeatureToggles;
import sence.playf.storetracker.registry.HeldItemLedger;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.ClientDelay;
import sence.playf.storetracker.util.MapKey;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 상자류(제네릭 컨테이너/셔틀박스)를 열고 닫을 때 슬롯을 스냅샷/비교해서 입출고를 감지한다.
 * - 서버에 등록된 "공용템 상자"라면 모든 아이템을 추적하고 제목을 강조 표시한다.
 * - 그 외의(경유) 상자는 이미 공용템으로 알려진 아이템 타입의 이동만 추적한다
 *   (다른 상자를 거쳐 갔다가 공용템 상자로 돌아오는 흐름을 최대한 따라가기 위함).
 */
public final class ContainerTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    private static BlockPos pendingPos;
    private static String pendingDimension;

    private static Map<String, Integer> openSnapshot;
    private static Map<String, String> itemNames;
    // 상자를 아주 빠르게 열었다 닫으면, 2틱 지연됐던 "열림 스냅샷" 콜백이 이미 닫힌 뒤에 실행돼서
    // 그 사이에 연 다른 상자의 상태를 오염시킬 수 있다. 그걸 막기 위한 세션 번호.
    private static long sessionCounter = 0;

    private static volatile ScreenHandler activeHandler;
    private static volatile Integer activeSlotCount;
    private static volatile ChestRegistry.RegisteredChest activeChest;

    private static SecureChannel channel;

    public static ScreenHandler activeHandler() {
        return activeHandler;
    }

    public static Integer activeSlotCount() {
        return activeSlotCount;
    }

    public static ChestRegistry.RegisteredChest activeChest() {
        return activeChest;
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient() || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }
            pendingPos = hitResult.getBlockPos();
            pendingDimension = world.getRegistryKey().getValue().toString();
            // 상자를 열 때마다 서버의 최신 등록 정보/보유 장부를 미리 받아둔다.
            // (예전 세션에 꺼내놓은 것도 여기서 다시 맞춰지므로 재접속 안 해도 [공용템] 표시가 늦게라도 정확해짐)
            Async.run(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                ChestRegistry.refresh(MapKey.current(client));
                HeldItemLedger.fetch(MapKey.current(client), client.getSession().getUsername());
                FeatureToggles.fetch(MapKey.current(client));
            });
            return ActionResult.PASS;
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            BlockPos pos = pendingPos;
            String dimension = pendingDimension;
            pendingPos = null;
            pendingDimension = null;
            if (pos == null || !(screen instanceof HandledScreen<?> handledScreen)) {
                return;
            }

            ScreenHandler handler = handledScreen.getScreenHandler();
            Integer slotCount = ContainerSlots.slotCountFor(handler);
            if (slotCount == null) {
                return;
            }

            Optional<ChestRegistry.RegisteredChest> registered = ChestRegistry.find(
                    dimension, pos.getX(), pos.getY(), pos.getZ());
            String chestLabel = registered.map(ChestRegistry.RegisteredChest::label).orElse(null);

            activeHandler = handler;
            activeSlotCount = slotCount;
            activeChest = registered.orElse(null);

            long session = ++sessionCounter;

            // 상자를 여는 순간엔 서버가 아직 실제 아이템 내용을 안 보내줘서 슬롯이 비어있다.
            // 바로 스냅샷을 뜨면 원래 있던 아이템이 전부 "방금 넣음"으로 오인식되므로 몇 틱 기다렸다가 뜬다.
            ClientDelay.runAfterTicks(2, () -> {
                if (session != sessionCounter) {
                    // 그 사이 이 상자가 닫히고 다른 상자가 열렸다 - 이 스냅샷은 이미 낡은 것이라 버린다.
                    return;
                }
                openSnapshot = ContainerSlots.snapshotCounts(handler, slotCount);
                itemNames = ContainerSlots.snapshotNames(handler, slotCount);
                sendChestLog("OPEN", chestLabel, dimension, pos, openSnapshot, itemNames);
            });

            registered.ifPresent(chest -> {
                // 실제 title은 건드리지 않고(이 서버 커스텀 GUI가 title로 배경을 고르는 듯해서 건드리면 배경이 깨짐),
                // 그 위에 강조 라벨만 매 프레임 따로 그린다.
                if (FeatureToggles.labelOverlay()) {
                    ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, tickDelta) ->
                            ChestLabelOverlay.draw(context, s, chest.label()));
                }

                // 개발자 빌드 전용: 상자가 열려 있는 동안은 채팅/명령어를 칠 수 없어서 버튼으로 대신 제공.
                if (BuildInfo.isDevBuild()) {
                    ButtonWidget saveButton = ButtonWidget.builder(
                                    Text.literal("현재 아이템 캡처"),
                                    button -> SaveItemsAction.run(client, handler, slotCount, dimension, pos))
                            .dimensions(screen.width - 108, 4, 100, 20)
                            .build();
                    ScreenButtons.add(screen, saveButton);

                    ButtonWidget strictButton = ButtonWidget.builder(
                                    Text.literal(chest.strictMode() ? "개인템 차단: ON" : "개인템 차단: OFF"),
                                    button -> ToggleStrictAction.run(client, dimension, pos))
                            .dimensions(screen.width - 108, 26, 100, 20)
                            .build();
                    ScreenButtons.add(screen, strictButton);
                }
            });

            ScreenEvents.remove(screen).register(closedScreen -> {
                activeHandler = null;
                activeSlotCount = null;
                activeChest = null;
                onContainerClosed(handler, slotCount, chestLabel, dimension, pos);
            });
        });
    }

    private static void onContainerClosed(ScreenHandler handler, int slotCount, String chestLabel,
                                           String dimension, BlockPos pos) {
        Map<String, Integer> before = openSnapshot;
        Map<String, String> names = itemNames;
        openSnapshot = null;
        itemNames = null;
        if (before == null) {
            return;
        }

        Map<String, Integer> after = ContainerSlots.snapshotCounts(handler, slotCount);
        names.putAll(ContainerSlots.snapshotNames(handler, slotCount));
        sendChestLog("CLOSE", chestLabel, dimension, pos, after, names);

        boolean isRegisteredChest = chestLabel != null;
        MinecraftClient client = MinecraftClient.getInstance();
        String username = client.getSession().getUsername();
        String mapKey = MapKey.current(client);
        String occurredAt = Instant.now().toString();

        Set<String> itemIds = new HashSet<>();
        itemIds.addAll(before.keySet());
        itemIds.addAll(after.keySet());

        for (String itemId : itemIds) {
            int delta = after.getOrDefault(itemId, 0) - before.getOrDefault(itemId, 0);
            if (delta == 0) {
                continue;
            }
            // 경유 상자에서는 내가 지금 보유 중이라고 장부에 있는 아이템만 추적한다.
            if (!isRegisteredChest && (!FeatureToggles.passthroughTracking() || !HeldItemLedger.isHeld(itemId))) {
                continue;
            }

            String action = delta < 0 ? "TAKE" : "DEPOSIT";
            if (isRegisteredChest) {
                // 서버 응답을 기다리지 않고 즉시 반영 - 체감 지연 없이 바로 [공용템] 표시/차단이 갱신된다.
                HeldItemLedger.adjustLocal(itemId, action.equals("TAKE") ? Math.abs(delta) : -Math.abs(delta));
            }

            JsonObject event = new JsonObject();
            event.addProperty("username", username);
            event.addProperty("item_id", itemId);
            event.addProperty("item_name", names.getOrDefault(itemId, itemId));
            event.addProperty("action", action);
            event.addProperty("count", Math.abs(delta));
            event.addProperty("occurred_at", occurredAt);
            event.addProperty("map_key", mapKey);
            if (chestLabel != null) {
                event.addProperty("chest_label", chestLabel);
            }
            JsonObject position = new JsonObject();
            position.addProperty("dimension", dimension);
            position.addProperty("x", pos.getX());
            position.addProperty("y", pos.getY());
            position.addProperty("z", pos.getZ());
            event.add("position", position);

            Async.run(() -> {
                LocalLogger.log(event);
                if (MemberGate.isAuthorized()) {
                    ApiClient.Result result = ApiClient.postEncrypted(
                            BuildInfo.API_BASE_URL, "/api/log-event", channel(), event);
                    if (result.statusCode() != 202) {
                        LOGGER.warn("이벤트 전송 실패 (status={})", result.statusCode());
                    }
                }
            });
        }
    }

    /** 등록된 공용템 상자를 열거나 닫는 순간의 전체 내용물 스냅샷을 개발자 로그용으로 서버에 보낸다. */
    private static void sendChestLog(String session, String chestLabel, String dimension, BlockPos pos,
                                      Map<String, Integer> counts, Map<String, String> names) {
        if (chestLabel == null || !MemberGate.isAuthorized() || !FeatureToggles.chestLog()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        String username = client.getSession().getUsername();
        String mapKey = MapKey.current(client);

        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("map_key", mapKey);
        body.addProperty("chest_label", chestLabel);
        body.addProperty("session", session);
        JsonObject position = new JsonObject();
        position.addProperty("dimension", dimension);
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        body.add("position", position);

        JsonArray items = new JsonArray();
        counts.forEach((itemId, count) -> {
            JsonObject item = new JsonObject();
            item.addProperty("item_id", itemId);
            item.addProperty("item_name", names.getOrDefault(itemId, itemId));
            item.addProperty("count", count);
            items.add(item);
        });
        body.add("items", items);

        Async.run(() -> {
            ApiClient.Result result = ApiClient.postEncrypted(BuildInfo.API_BASE_URL, "/api/chest-log", channel(), body);
            if (result.statusCode() != 200) {
                LOGGER.warn("상자 열림/닫힘 로그 전송 실패 (status={})", result.statusCode());
            }
        });
    }

    private static synchronized SecureChannel channel() {
        if (channel == null) {
            channel = new SecureChannel(BuildInfo.APP_SECRET);
        }
        return channel;
    }

    private ContainerTracker() {
    }
}
