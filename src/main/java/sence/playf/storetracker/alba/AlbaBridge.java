package sence.playf.storetracker.alba;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 우리 서버의 {@code /api/alba/*}에 암호화 채널로 통신한다 (평문으로 통신하던 playf의 AlbaBridge와
 * 달리, ContainerTracker가 이미 쓰는 SecureChannel/ApiClient를 그대로 재사용한다). */
public final class AlbaBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static SecureChannel channel;

    public void hire(String employer, String employee) {
        JsonObject body = baseBody(employer);
        body.addProperty("employee", employee);
        postAsync("/api/alba/hire", body, "알바 고용(" + employer + " -> " + employee + ")");
    }

    public void fire(String employer, String employee) {
        JsonObject body = baseBody(employer);
        body.addProperty("employee", employee);
        postAsync("/api/alba/fire", body, "알바 해고(" + employer + " -> " + employee + ")");
    }

    public void setJob(String employer, String employee, String job, String zone) {
        JsonObject body = baseBody(employer);
        body.addProperty("employee", employee);
        body.addProperty("job", job);
        if (zone != null) {
            body.addProperty("zone", zone);
        }
        postAsync("/api/alba/job", body, "알바 작업 설정(" + employer + " -> " + employee + " = " + job + ")");
    }

    /** 렌더 스레드를 막지 않는다 - 결과는 콜백으로 돌아온다. 실패하면 빈 목록을 돌려준다. */
    public void fetchStatus(java.util.function.Consumer<List<AlbaEntry>> onResult) {
        MinecraftClient client = MinecraftClient.getInstance();
        String mapKey = MapKey.current(client);
        Async.run(() -> {
            ApiClient.Result result = ApiClient.get(BuildInfo.API_BASE_URL,
                    "/api/alba/status?map=" + encode(mapKey));
            if (!result.isSuccess() || result.body() == null) {
                onResult.accept(List.of());
                return;
            }
            List<AlbaEntry> entries = new ArrayList<>();
            JsonObject root = result.bodyAsJson().getAsJsonObject();
            JsonArray hires = root.has("hires") ? root.getAsJsonArray("hires") : new JsonArray();
            for (var el : hires) {
                JsonObject h = el.getAsJsonObject();
                String employer = h.get("employer").getAsString();
                String employee = h.get("employee").getAsString();
                String job = h.has("job") && !h.get("job").isJsonNull() ? h.get("job").getAsString() : null;
                String zone = h.has("zone") && !h.get("zone").isJsonNull() ? h.get("zone").getAsString() : null;
                long hiredAtMillis = parseMillis(h.get("hired_at").getAsString());
                entries.add(new AlbaEntry(employer, employee, job, zone, hiredAtMillis));
            }
            onResult.accept(entries);
        });
    }

    private static long parseMillis(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private JsonObject baseBody(String employer) {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject body = new JsonObject();
        body.addProperty("username", client.getSession().getUsername());
        body.addProperty("map_key", MapKey.current(client));
        body.addProperty("employer", employer);
        return body;
    }

    private void postAsync(String path, JsonObject body, String context) {
        Async.run(() -> {
            ApiClient.Result result = ApiClient.postEncrypted(BuildInfo.API_BASE_URL, path, channel(), body);
            if (!result.isSuccess()) {
                LOGGER.warn("{} 전송 실패 (status={})", context, result.statusCode());
            }
        });
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static synchronized SecureChannel channel() {
        if (channel == null) {
            channel = new SecureChannel(BuildInfo.APP_SECRET);
        }
        return channel;
    }
}
