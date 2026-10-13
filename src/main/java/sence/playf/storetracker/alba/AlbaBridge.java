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

    /** 렌더 스레드를 막지 않는다 - 결과는 콜백으로 돌아온다. 실패하면 빈 목록을 돌려준다.
     * AlbaTracker.pollNow()의 "polling" 플래그는 이 콜백이 호출돼야 풀리는데, 여기서 예외가 밖으로
     * 새 나가면(Async.run이 잡아서 로그는 남기지만) 콜백이 영영 안 불려서 그 플래그가 true로
     * 멈춘 채 다시는 안 풀린다 - 그러면 HUD/알바 수정 화면이 그 시점 내용으로 영구히 멈춰버리고
     * 재접속 전까진 절대 안 풀린다("동기화가 이상하다"는 신고의 유력한 원인). 그래서 파싱 도중
     * 뭐가 터지든 반드시 onResult가 불리게 try-catch로 감싼다. */
    public void fetchStatus(java.util.function.Consumer<List<AlbaEntry>> onResult) {
        MinecraftClient client = MinecraftClient.getInstance();
        String mapKey = MapKey.current(client);
        Async.run(() -> onResult.accept(fetchEntries("/api/alba/status?map=" + encode(mapKey))));
    }

    /** 해고는 됐는데 아직 "몇 상자 채웠는지"를 안 적은 내 알바 기록 - 알바 수정 화면의 "기록 대기
     * 중" 목록에 쓴다. */
    public void fetchPendingRecords(String employer, java.util.function.Consumer<List<AlbaEntry>> onResult) {
        MinecraftClient client = MinecraftClient.getInstance();
        String mapKey = MapKey.current(client);
        Async.run(() -> onResult.accept(
                fetchEntries("/api/alba/pending-records?map=" + encode(mapKey) + "&employer=" + encode(employer))));
    }

    private List<AlbaEntry> fetchEntries(String path) {
        try {
            ApiClient.Result result = ApiClient.get(BuildInfo.API_BASE_URL, path);
            if (!result.isSuccess() || result.body() == null) {
                return List.of();
            }
            List<AlbaEntry> entries = new ArrayList<>();
            JsonObject root = result.bodyAsJson().getAsJsonObject();
            JsonArray hires = root.has("hires") ? root.getAsJsonArray("hires") : new JsonArray();
            for (var el : hires) {
                entries.add(parseEntry(el.getAsJsonObject()));
            }
            return entries;
        } catch (Exception e) {
            LOGGER.warn("[알바] 목록 응답 파싱 실패 ({}): {}", path, e.toString());
            return List.of();
        }
    }

    /** 작업별 상자당 기본 단가 - "몇 상자?" 입력창을 열 때 기본값으로 미리 채운다. */
    public void fetchWageRates(java.util.function.Consumer<java.util.Map<String, Integer>> onResult) {
        MinecraftClient client = MinecraftClient.getInstance();
        String mapKey = MapKey.current(client);
        Async.run(() -> {
            java.util.Map<String, Integer> rates = new java.util.HashMap<>();
            try {
                ApiClient.Result result = ApiClient.get(BuildInfo.API_BASE_URL, "/api/alba/wage-rates?map=" + encode(mapKey));
                if (result.isSuccess() && result.body() != null) {
                    JsonObject root = result.bodyAsJson().getAsJsonObject();
                    for (var e : root.entrySet()) {
                        if (!e.getValue().isJsonNull()) {
                            rates.put(e.getKey(), e.getValue().getAsInt());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[알바] 단가 응답 파싱 실패: {}", e.toString());
            }
            onResult.accept(rates);
        });
    }

    /** 몇 상자 채웠는지(+ 선택적으로 단가 덮어쓰기)를 서버에 기록한다. */
    public void record(long hireId, int boxCount, Integer unitPriceOverride, Runnable onDone) {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject body = new JsonObject();
        body.addProperty("username", client.getSession().getUsername());
        body.addProperty("map_key", MapKey.current(client));
        body.addProperty("hire_id", hireId);
        body.addProperty("box_count", boxCount);
        if (unitPriceOverride != null) {
            body.addProperty("unit_price", unitPriceOverride);
        }
        Async.run(() -> {
            ApiClient.Result result = ApiClient.postEncrypted(BuildInfo.API_BASE_URL, "/api/alba/record", channel(), body);
            if (!result.isSuccess()) {
                LOGGER.warn("알바 기록 전송 실패 (status={})", result.statusCode());
            }
            if (onDone != null) {
                client.execute(onDone);
            }
        });
    }

    private static AlbaEntry parseEntry(JsonObject h) {
        long id = h.has("id") && !h.get("id").isJsonNull() ? h.get("id").getAsLong() : 0L;
        String employer = h.get("employer").getAsString();
        String employee = h.get("employee").getAsString();
        String job = h.has("job") && !h.get("job").isJsonNull() ? h.get("job").getAsString() : null;
        String zone = h.has("zone") && !h.get("zone").isJsonNull() ? h.get("zone").getAsString() : null;
        long hiredAtMillis = parseMillis(h.get("hired_at").getAsString());
        return new AlbaEntry(id, employer, employee, job, zone, hiredAtMillis);
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
                // 예전엔 로그에만 남기고 조용히 넘어갔다 - 고용/해고가 감지는 됐는데(로그엔 찍힘)
                // 서버 저장은 실패하면(예: 아직 등록 안 된 닉네임이라 404) 아무 화면에도 티가 안 나서
                // 플레이어는 "왜 안 뜨지"하며 같은 명령을 여러 번 반복하게 됐다. 재시도는 버그
                // 위험이 있어 일부러 안 넣기로 했었지만(이 모드 다른 기능에서도 같은 원칙), 최소한
                // 눈에 보이는 경고는 띄워서 뭔가 실패했다는 걸 바로 알 수 있게 한다.
                notifyFailure(context, result.statusCode());
            }
        });
    }

    private static void notifyFailure(String context, int statusCode) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                String reason = statusCode == 404
                        ? "등록된 멤버가 아니에요(대시보드에서 먼저 로그인/연동 확인해주세요)"
                        : "status=" + statusCode;
                client.player.sendMessage(net.minecraft.text.Text.literal(
                        "[StoreTracker] " + context + " 전송 실패 - " + reason), false);
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
