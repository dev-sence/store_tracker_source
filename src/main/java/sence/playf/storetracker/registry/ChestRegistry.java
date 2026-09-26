package sence.playf.storetracker.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.config.ModConfig;
import sence.playf.storetracker.config.TrackedPosition;
import sence.playf.storetracker.net.ApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 서버(Flask)에 등록된 공용템 상자 목록을 접속한 맵(서버 주소) 기준으로 캐싱한다.
 * 접속할 때, 그리고 상자를 열 때마다 {@link #refresh(String)}으로 새로 받아온다.
 */
public final class ChestRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    public record RegisteredChest(String dimension, int x, int y, int z, String label, boolean strictMode) {
        public boolean matches(String dimension, int x, int y, int z) {
            return this.dimension.equals(dimension) && this.x == x && this.y == y && this.z == z;
        }
    }

    private static volatile List<RegisteredChest> chests = List.of();
    private static volatile String mapKey = "";

    public static void refresh(String mapKey) {
        ChestRegistry.mapKey = mapKey;

        ApiClient.Result chestResult = ApiClient.get(BuildInfo.API_BASE_URL, "/api/chests?map=" + encode(mapKey));
        if (chestResult.isSuccess() && chestResult.body() != null) {
            List<RegisteredChest> parsed = new ArrayList<>();
            for (JsonElement element : chestResult.bodyAsJson().getAsJsonArray()) {
                JsonObject obj = element.getAsJsonObject();
                parsed.add(new RegisteredChest(
                        obj.get("dimension").getAsString(),
                        obj.get("x").getAsInt(),
                        obj.get("y").getAsInt(),
                        obj.get("z").getAsInt(),
                        obj.get("label").getAsString(),
                        obj.has("strict_mode") && obj.get("strict_mode").getAsBoolean()));
            }
            chests = parsed;
        } else {
            LOGGER.warn("공용템 상자 목록을 불러오지 못했습니다 (status={})", chestResult.statusCode());
        }
    }

    // 라지(더블) 상자는 블록이 2칸이라, 등록할 때 클릭한 쪽과 실제로 여는 쪽이 다를 수 있다.
    // 정확히 일치하는 게 없으면 가로로 붙어있는 4칸(더블 상자의 반대쪽 절반)도 확인한다.
    private static final int[][] HORIZONTAL_NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static Optional<RegisteredChest> find(String dimension, int x, int y, int z) {
        Optional<RegisteredChest> exact = findExact(dimension, x, y, z);
        if (exact.isPresent()) {
            return exact;
        }
        for (int[] offset : HORIZONTAL_NEIGHBORS) {
            Optional<RegisteredChest> neighbor = findExact(dimension, x + offset[0], y, z + offset[1]);
            if (neighbor.isPresent()) {
                return neighbor;
            }
        }
        return Optional.empty();
    }

    private static Optional<RegisteredChest> findExact(String dimension, int x, int y, int z) {
        for (RegisteredChest chest : chests) {
            if (chest.matches(dimension, x, y, z)) {
                return Optional.of(chest);
            }
        }
        for (TrackedPosition local : ModConfig.get().trackedPositions) {
            if (local.matches(dimension, x, y, z)) {
                return Optional.of(new RegisteredChest(dimension, x, y, z, "공용템 상자", false));
            }
        }
        return Optional.empty();
    }

    public static String currentMapKey() {
        return mapKey;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private ChestRegistry() {
    }
}
