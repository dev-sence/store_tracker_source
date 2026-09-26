package sence.playf.storetracker.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.net.ApiClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "내가 등록 상자에서 꺼내서 지금 들고 있다고 인정되는" 아이템 타입별 개수를 서버 장부와 맞춰 캐싱한다.
 * 실제 아이템에는 아무 표시도 남기지 않는다 (그럴 방법이 없음) - 순전히 이 카운트로만 공용템 여부를 판단한다.
 * 이름이 같은 개인 아이템이라도 이 카운트가 0이면 공용템으로 취급하지 않는다.
 */
public final class HeldItemLedger {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    // fetch()가 clear()+putAll()로 두 단계에 걸쳐 갱신하면 그 찰나에 다른 스레드(툴팁 렌더링)가
    // 일시적으로 텅 빈 맵을 볼 수 있다. 그래서 새 맵을 통째로 만든 다음 참조를 한 번에 갈아끼운다.
    private static volatile Map<String, Integer> counts = new ConcurrentHashMap<>();

    public static void fetch(String mapKey, String username) {
        ApiClient.Result result = ApiClient.get(BuildInfo.API_BASE_URL,
                "/api/held-items?map=" + encode(mapKey) + "&username=" + encode(username));
        if (!result.isSuccess() || result.body() == null) {
            LOGGER.warn("보유 장부를 불러오지 못했습니다 (status={})", result.statusCode());
            return;
        }

        Map<String, Integer> parsed = new ConcurrentHashMap<>();
        for (JsonElement element : result.bodyAsJson().getAsJsonArray()) {
            JsonObject obj = element.getAsJsonObject();
            parsed.put(obj.get("item_id").getAsString(), obj.get("held_count").getAsInt());
        }
        counts = parsed;
    }

    /** 서버 응답을 기다리지 않고 방금 한 TAKE/DEPOSIT을 즉시 로컬에 반영한다 (체감 지연 없게). */
    public static void adjustLocal(String itemId, int delta) {
        counts.merge(itemId, delta, (oldValue, d) -> Math.max(0, oldValue + d));
    }

    public static int heldCount(String itemId) {
        return counts.getOrDefault(itemId, 0);
    }

    public static boolean isHeld(String itemId) {
        return heldCount(itemId) > 0;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private HeldItemLedger() {
    }
}
