package sence.playf.storetracker.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.registry.ChestRegistry;
import sence.playf.storetracker.tracker.ContainerSlots;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

import java.util.Map;

/**
 * 개발자 빌드 전용: 지금 열려 있는 공용템 상자 속 내용물(아이템 타입 + 수량)을 통째로 서버에 캡처해서
 * "공용템 목록"과 "이 상자의 기준 재고"로 동시에 저장한다. 이후 이 상자에서 꺼내면 출고, 넣으면 입고로
 * 서버 재고가 실시간으로 갱신된다.
 * "현재 아이템 저장" 버튼에서 호출된다 (상자가 열린 상태에서는 채팅/명령어를 칠 수 없어서 버튼으로 구현).
 */
public final class SaveItemsAction {

    public static void run(MinecraftClient client, ScreenHandler handler, int slotCount,
                            String dimension, BlockPos pos) {
        Map<String, Integer> counts = ContainerSlots.snapshotCounts(handler, slotCount);
        Map<String, String> names = ContainerSlots.snapshotNames(handler, slotCount);
        if (client.player == null) {
            return;
        }
        if (names.isEmpty()) {
            client.player.sendMessage(Text.literal("§c[StoreTracker] 상자가 비어 있습니다."), false);
            return;
        }

        String mapKey = MapKey.current(client);
        String username = client.getSession().getUsername();
        client.player.sendMessage(Text.literal("§e[StoreTracker] 공용템 " + names.size() + "종 캡처 중..."), false);

        Async.run(() -> {
            SecureChannel channel = new SecureChannel(BuildInfo.APP_SECRET);
            JsonObject body = new JsonObject();
            body.addProperty("map_key", mapKey);
            body.addProperty("username", username);

            JsonObject position = new JsonObject();
            position.addProperty("dimension", dimension);
            position.addProperty("x", pos.getX());
            position.addProperty("y", pos.getY());
            position.addProperty("z", pos.getZ());
            body.add("position", position);

            JsonArray items = new JsonArray();
            names.forEach((itemId, displayName) -> {
                JsonObject item = new JsonObject();
                item.addProperty("item_id", itemId);
                item.addProperty("display_name", displayName);
                item.addProperty("count", counts.getOrDefault(itemId, 0));
                items.add(item);
            });
            body.add("items", items);

            ApiClient.Result result = ApiClient.postEncrypted(
                    BuildInfo.API_BASE_URL, "/api/public-items", channel, body, BuildInfo.ADMIN_TOKEN);

            // refresh()도 네트워크 호출이라 렌더 스레드가 아니라 여기(백그라운드)에서 끝내둔다.
            if (result.isSuccess()) {
                ChestRegistry.refresh(mapKey);
            }

            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                if (result.isSuccess()) {
                    client.player.sendMessage(Text.literal(
                            "§a[StoreTracker] 공용템 " + names.size() + "종 캡처 완료 (기준 재고로 저장됨)"), false);
                } else {
                    client.player.sendMessage(Text.literal(
                            "§c[StoreTracker] 저장 실패 (status=" + result.statusCode() + ")"), false);
                }
            });
        });
    }

    private SaveItemsAction() {
    }
}
