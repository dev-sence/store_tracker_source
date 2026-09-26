package sence.playf.storetracker.registry;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.net.ApiClient;

/**
 * 여러 기능(상자 제목 강조, [공용템] 표시, 경유 상자 추적, 상자 열림/닫힘 로그)의 on/off 상태를
 * 서버에서 맵별로 받아와 캐싱한다. 개발자 빌드의 {@code /dev_toggle}이 서버 값을 바꾸면, 같은 맵에
 * 접속한 배포용/개발용 빌드 유저 모두가 (재접속 없이도 주기적 재조회로) 곧 그 상태를 따라간다.
 */
public final class FeatureToggles {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    private static volatile boolean labelOverlay = true;
    private static volatile boolean publicTag = true;
    private static volatile boolean passthroughTracking = true;
    private static volatile boolean chestLog = true;

    public static void fetch(String mapKey) {
        ApiClient.Result result = ApiClient.get(BuildInfo.API_BASE_URL,
                "/api/feature-toggles?map=" + encode(mapKey));
        if (!result.isSuccess() || result.body() == null) {
            LOGGER.warn("기능 토글 상태를 불러오지 못했습니다 (status={})", result.statusCode());
            return;
        }

        JsonObject obj = result.bodyAsJson().getAsJsonObject();
        labelOverlay = obj.get("label_overlay").getAsBoolean();
        publicTag = obj.get("public_tag").getAsBoolean();
        passthroughTracking = obj.get("passthrough_tracking").getAsBoolean();
        chestLog = obj.get("chest_log").getAsBoolean();
    }

    public static boolean labelOverlay() {
        return labelOverlay;
    }

    public static boolean publicTag() {
        return publicTag;
    }

    public static boolean passthroughTracking() {
        return passthroughTracking;
    }

    public static boolean chestLog() {
        return chestLog;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private FeatureToggles() {
    }
}
