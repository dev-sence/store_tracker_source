package sence.playf.storetracker.display;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.registry.ChestRegistry;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

/**
 * 개발자 빌드 전용: 등록된 공용템 상자의 "개인템 차단" on/off를 서버에서 토글한다.
 * 꺼져 있으면 배포용 빌드 사용자도 아무 아이템이나 넣을 수 있다 (초기 재고 채우기 등에 사용).
 */
public final class ToggleStrictAction {

    public static void run(MinecraftClient client, String dimension, BlockPos pos) {
        String mapKey = MapKey.current(client);
        String username = client.getSession().getUsername();

        Async.run(() -> {
            SecureChannel channel = new SecureChannel(BuildInfo.APP_SECRET);
            JsonObject body = new JsonObject();
            body.addProperty("map_key", mapKey);
            body.addProperty("dimension", dimension);
            body.addProperty("x", pos.getX());
            body.addProperty("y", pos.getY());
            body.addProperty("z", pos.getZ());
            body.addProperty("username", username);

            ApiClient.Result result = ApiClient.postEncrypted(
                    BuildInfo.API_BASE_URL, "/api/chests/toggle-strict", channel, body, BuildInfo.ADMIN_TOKEN);

            if (result.isSuccess()) {
                ChestRegistry.refresh(mapKey);
            }

            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                if (result.isSuccess()) {
                    client.player.sendMessage(Text.literal(
                            "§a[StoreTracker] 개인템 차단 상태 변경됨 (상자를 다시 열면 버튼에 반영됩니다)"), false);
                } else {
                    client.player.sendMessage(Text.literal(
                            "§c[StoreTracker] 변경 실패 (status=" + result.statusCode() + ")"), false);
                }
            });
        });
    }

    private ToggleStrictAction() {
    }
}
