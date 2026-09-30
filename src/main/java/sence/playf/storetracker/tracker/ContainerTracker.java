package sence.playf.storetracker.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
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
 *
 * 입출고 감지는 "열림~닫힘 전체 구간"이 아니라 클릭 한 번 단위로 이뤄진다({@link #beforeClick()}/
 * {@link #afterClick()}). 바닐라 상자는 여러 플레이어가 동시에 열어볼 수 있는데, 열림~닫힘 전체를
 * 기준으로 diff를 뜨면 그 사이에 "다른 사람"이 넣거나 뺀 것까지 내가 한 것처럼 잘못 기록되고 중복으로
 * 찍히는 문제가 있었다. 클릭 직전/직후로 범위를 좁히면 그 순간 실제로 내가 클릭해서 생긴 변화만 잡을 수
 * 있어서 이 문제가 없다.
 */
public final class ContainerTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    private static BlockPos pendingPos;
    private static String pendingDimension;

    // 상자를 아주 빠르게 열었다 닫으면, 2틱 지연됐던 "열림 스냅샷" 콜백이 이미 닫힌 뒤에 실행돼서
    // 그 사이에 연 다른 상자의 상태를 오염시킬 수 있다. 그걸 막기 위한 세션 번호.
    private static long sessionCounter = 0;

    private static volatile ScreenHandler activeHandler;
    private static volatile Integer activeSlotCount;
    private static volatile ChestRegistry.RegisteredChest activeChest;
    private static volatile String activeDimension;
    private static volatile BlockPos activePos;

    // 클릭 한 번의 전/후 스냅샷을 잠깐 들고 있는 용도 (beforeClick -> afterClick 사이에만 값이 있음).
    private static Map<String, Integer> pendingClickBefore;
    private static StackInfo pendingCursorBefore;
    private static Boolean pendingTargetIsContainerSlot;

    // "지금 커서에 들고 있는 아이템이 이 상자에서 집은 것인지"를 클릭 사이사이 계속 들고 있는다.
    // PICKUP은 "슬롯 -> 커서 -> 슬롯" 두 번의 클릭에 걸쳐 일어나서, 두 번째 클릭(내려놓기) 시점에
    // 첫 번째 클릭(집기)이 어디서 왔는지 알아야 진짜 입출고인지 상자 안에서의 자리 이동인지 구분된다.
    private static Boolean cursorFromContainer;

    /** 커서(또는 슬롯)에 들린 아이템 한 종류의 정체와 개수. */
    private record StackInfo(String id, String name, int count) {
    }

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

            // 컨테이너 화면이 열릴 때마다(클릭마다가 아니라) 한 번만 찍히는 진단 로그 - 특정 유저에게서
            // 입출고 추적이 통째로 안 되는 문제를 조사할 때, 애초에 이 지점까지 오는지/등록된 상자로
            // 인식됐는지를 바로 확인하기 위함.
            LOGGER.info("컨테이너 화면 열림: dimension={} pos=({},{},{}) registered={} slotCount={}",
                    dimension, pos.getX(), pos.getY(), pos.getZ(),
                    registered.map(ChestRegistry.RegisteredChest::label).orElse("(미등록)"), slotCount);

            // 큰 상자(더블 상자)는 블록이 2칸이라 어느 쪽을 클릭했느냐에 따라 실제 좌표가 달라지는데,
            // 서버 쪽 "이 상자의 재고"는 좌표를 키로 쓰기 때문에 그대로 보내면 같은 상자인데도
            // 양쪽 좌표에 재고가 각각 따로 쌓여서 합계가 부풀어 보이는 문제가 있었다. 그래서 등록된
            // 상자라면 항상 등록 당시의 좌표(정본)로 통일해서 보낸다 - 어느 쪽을 열든 좌표는 하나로 고정.
            String chestDimension = registered.map(ChestRegistry.RegisteredChest::dimension).orElse(dimension);
            BlockPos chestPos = registered
                    .map(c -> new BlockPos(c.x(), c.y(), c.z()))
                    .orElse(pos);

            activeHandler = handler;
            activeSlotCount = slotCount;
            activeChest = registered.orElse(null);
            activeDimension = chestDimension;
            activePos = chestPos;
            cursorFromContainer = null;

            long session = ++sessionCounter;

            // 상자를 여는 순간엔 서버가 아직 실제 아이템 내용을 안 보내줘서 슬롯이 비어있다.
            // 바로 스냅샷을 뜨면 원래 있던 아이템이 전부 "방금 넣음"으로 오인식되므로 몇 틱 기다렸다가 뜬다.
            // (이 스냅샷은 개발자 로그용 감사 기록일 뿐, 입출고 감지에는 더 이상 쓰이지 않는다.)
            ClientDelay.runAfterTicks(2, () -> {
                if (session != sessionCounter) {
                    // 그 사이 이 상자가 닫히고 다른 상자가 열렸다 - 이 스냅샷은 이미 낡은 것이라 버린다.
                    return;
                }
                Map<String, Integer> counts = ContainerSlots.snapshotCounts(handler, slotCount);
                Map<String, String> names = ContainerSlots.snapshotNames(handler, slotCount);
                sendChestLog("OPEN", chestLabel, chestDimension, chestPos, counts, names);
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
                                    button -> SaveItemsAction.run(client, handler, slotCount, chestDimension, chestPos))
                            .dimensions(screen.width - 108, 4, 100, 20)
                            .build();
                    ScreenButtons.add(screen, saveButton);

                    ButtonWidget strictButton = ButtonWidget.builder(
                                    Text.literal(chest.strictMode() ? "개인템 차단: ON" : "개인템 차단: OFF"),
                                    button -> ToggleStrictAction.run(client, chestDimension, chestPos))
                            .dimensions(screen.width - 108, 26, 100, 20)
                            .build();
                    ScreenButtons.add(screen, strictButton);
                }
            });

            ScreenEvents.remove(screen).register(closedScreen -> {
                activeHandler = null;
                activeSlotCount = null;
                activeChest = null;
                activeDimension = null;
                activePos = null;
                pendingClickBefore = null;
                cursorFromContainer = null;
                onContainerClosed(handler, slotCount, chestLabel, chestDimension, chestPos);
            });
        });
    }

    private static StackInfo readCursor(ScreenHandler handler) {
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            return null;
        }
        return new StackInfo(ContainerSlots.identityKey(cursor), cursor.getName().getString(), cursor.getCount());
    }

    /** 슬롯 클릭 처리(onMouseClick) 직전에 호출한다 - 지금 상자 슬롯/커서 상태를 기록해둔다. */
    public static void beforeClick(Slot slot, SlotActionType actionType) {
        ScreenHandler handler = activeHandler;
        Integer slotCount = activeSlotCount;
        if (handler == null || slotCount == null) {
            pendingClickBefore = null;
            pendingCursorBefore = null;
            pendingTargetIsContainerSlot = null;
            return;
        }
        pendingClickBefore = ContainerSlots.snapshotCounts(handler, slotCount);
        pendingCursorBefore = readCursor(handler);
        pendingTargetIsContainerSlot = slot != null && slot.id < slotCount;
    }

    /**
     * 슬롯 클릭 처리 직후에 호출한다. PICKUP/QUICK_CRAFT(커서를 거치는 클릭)는 커서 출처 추적으로,
     * QUICK_MOVE/SWAP처럼 클릭 한 번 안에서 바로 끝나는 액션은 기존 슬롯 스냅샷 비교로 판단한다
     * (다른 플레이어가 같은 상자에서 동시에 만든 변화는 각자의 클라이언트가 각자 잡으므로 여기 안 섞인다).
     */
    public static void afterClick(Slot slot, SlotActionType actionType) {
        Map<String, Integer> before = pendingClickBefore;
        StackInfo cursorBefore = pendingCursorBefore;
        Boolean targetIsContainerSlot = pendingTargetIsContainerSlot;
        pendingClickBefore = null;
        pendingCursorBefore = null;
        pendingTargetIsContainerSlot = null;

        ScreenHandler handler = activeHandler;
        Integer slotCount = activeSlotCount;
        ChestRegistry.RegisteredChest chest = activeChest;
        String dimension = activeDimension;
        BlockPos pos = activePos;
        if (before == null || handler == null || slotCount == null || dimension == null || pos == null
                || targetIsContainerSlot == null) {
            return;
        }

        String chestLabel = chest != null ? chest.label() : null;
        boolean isRegisteredChest = chestLabel != null;

        if (actionType == SlotActionType.PICKUP || actionType == SlotActionType.QUICK_CRAFT) {
            handleCursorTransition(handler, cursorBefore, targetIsContainerSlot, isRegisteredChest, chestLabel,
                    dimension, pos);
            return;
        }

        // QUICK_MOVE(shift-클릭)/SWAP(숫자키 교환)은 커서를 거치지 않고 클릭 한 번 안에서 바로
        // "이 인벤토리 <-> 저 인벤토리"로 끝나서, 슬롯 스냅샷 비교만으로 충분하다.
        //
        // 바닐라 컨테이너 클릭은 "낙관적 예측"이다 - 클라이언트가 클릭 즉시 슬롯을 먼저 바꿔서
        // 보여주고, 서버가 그 이동을 거부하면(귀속템이라 상자에 못 넣는 경우 등) 나중에 정정
        // 패킷으로 되돌린다. 그래서 클릭 직후 바로 스냅샷을 뜨지 않고 몇 틱 기다렸다가 다시 떠서,
        // 그 사이 서버가 되돌리지 않았는지 확인한 뒤에야 진짜 변화로 인정한다.
        ClientDelay.runAfterTicks(4, () -> {
            Map<String, Integer> after = ContainerSlots.snapshotCounts(handler, slotCount);
            Map<String, String> names = ContainerSlots.snapshotNames(handler, slotCount);
            before.keySet().forEach(id -> names.putIfAbsent(id, id));

            Set<String> itemIds = new HashSet<>();
            itemIds.addAll(before.keySet());
            itemIds.addAll(after.keySet());
            for (String itemId : itemIds) {
                int delta = after.getOrDefault(itemId, 0) - before.getOrDefault(itemId, 0);
                if (delta == 0) {
                    continue;
                }
                String action = delta < 0 ? "TAKE" : "DEPOSIT";
                fireEvent(action, itemId, names.getOrDefault(itemId, itemId), Math.abs(delta),
                        isRegisteredChest, chestLabel, dimension, pos);
            }
        });
    }

    /**
     * PICKUP/QUICK_CRAFT 클릭 뒤 커서 변화를 본다. 상자 슬롯 <-> 커서 <-> 플레이어 인벤토리로 두 번의
     * 클릭에 걸쳐 일어나는 이동을, 커서에 든 아이템이 "어디서 왔는지"(cursorFromContainer)를 클릭
     * 사이사이 기억해뒀다가 판단한다 - 상자 안에서 슬롯만 옮기는 것(자리 정리, 캡처 준비 등)은 커서가
     * 상자 슬롯에서 나왔다가 다시 상자 슬롯으로 들어가므로 진짜 입출고로 잡히지 않는다.
     */
    private static void handleCursorTransition(ScreenHandler handler, StackInfo cursorBefore,
                                                 boolean targetIsContainerSlot, boolean isRegisteredChest,
                                                 String chestLabel, String dimension, BlockPos pos) {
        ClientDelay.runAfterTicks(4, () -> {
            StackInfo cursorAfter = readCursor(handler);

            // 커서에서 클릭한 슬롯으로 "내려놓인" 양 - 상자 슬롯에 내려놨는데 원래 플레이어 것이었으면
            // 진짜 DEPOSIT, 플레이어 슬롯에 내려놨는데 원래 상자 것이었으면 진짜 TAKE. 원래 있던 곳과
            // 같은 쪽으로 다시 들어간 거면(예: 상자 안에서 자리만 옮김) 아무 것도 안 보낸다.
            if (cursorBefore != null) {
                int left;
                if (cursorAfter == null || !cursorAfter.id().equals(cursorBefore.id())) {
                    left = cursorBefore.count();
                } else {
                    left = Math.max(0, cursorBefore.count() - cursorAfter.count());
                }
                if (left > 0) {
                    boolean originWasContainer = Boolean.TRUE.equals(cursorFromContainer);
                    if (targetIsContainerSlot && !originWasContainer) {
                        fireEvent("DEPOSIT", cursorBefore.id(), cursorBefore.name(), left,
                                isRegisteredChest, chestLabel, dimension, pos);
                    } else if (!targetIsContainerSlot && originWasContainer) {
                        fireEvent("TAKE", cursorBefore.id(), cursorBefore.name(), left,
                                isRegisteredChest, chestLabel, dimension, pos);
                    }
                }
            }

            // 이 클릭으로 커서에 새로 실린(또는 늘어난) 몫의 출처를 다음 클릭을 위해 기억해둔다.
            if (cursorAfter != null) {
                boolean grewOrChanged = cursorBefore == null
                        || !cursorBefore.id().equals(cursorAfter.id())
                        || cursorAfter.count() > cursorBefore.count();
                if (grewOrChanged) {
                    cursorFromContainer = targetIsContainerSlot;
                }
            } else {
                cursorFromContainer = null;
            }
        });
    }

    private static void fireEvent(String action, String itemId, String itemName, int count,
                                   boolean isRegisteredChest, String chestLabel, String dimension, BlockPos pos) {
        // 경유 상자에서는 내가 지금 보유 중이라고 장부에 있는 아이템만 추적한다.
        if (!isRegisteredChest && (!FeatureToggles.passthroughTracking() || !HeldItemLedger.isHeld(itemId))) {
            return;
        }

        LOGGER.info("입출고 감지: action={} item={} count={} registeredChest={}",
                action, itemName, count, isRegisteredChest);
        if (isRegisteredChest) {
            // 서버 응답을 기다리지 않고 즉시 반영 - 체감 지연 없이 바로 [공용템] 표시/차단이 갱신된다.
            HeldItemLedger.adjustLocal(itemId, action.equals("TAKE") ? count : -count);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        String username = client.getSession().getUsername();
        String mapKey = MapKey.current(client);
        String occurredAt = Instant.now().toString();

        JsonObject event = new JsonObject();
        event.addProperty("username", username);
        event.addProperty("item_id", itemId);
        event.addProperty("item_name", itemName);
        event.addProperty("action", action);
        event.addProperty("count", count);
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

    /** 상자가 닫힐 때의 전체 내용물 스냅샷을 개발자 로그용으로 서버에 보낸다 (감사용, 입출고 감지와는 무관). */
    private static void onContainerClosed(ScreenHandler handler, int slotCount, String chestLabel,
                                           String dimension, BlockPos pos) {
        Map<String, Integer> counts = ContainerSlots.snapshotCounts(handler, slotCount);
        Map<String, String> names = ContainerSlots.snapshotNames(handler, slotCount);
        sendChestLog("CLOSE", chestLabel, dimension, pos, counts, names);
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
