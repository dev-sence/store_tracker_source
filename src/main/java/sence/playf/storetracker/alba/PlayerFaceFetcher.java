package sence.playf.storetracker.alba;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** 알바 GUI/HUD에 쓸 플레이어 얼굴(8x8, 스킨 정면+모자 레이어) 텍스처를 받아와 캐싱한다. 실제
 * 업로드(텍스처 등록)는 {@link AlbaPlatform#uploadFaceTexture}에 맡긴다 - 그 타입(NativeImageBackedTexture
 * 생성자)이 1.21.x 버전마다 달라서, richFeaturesAvailable()이 false인 빌드에서는 null만 돌아온다
 * (호출 쪽이 얼굴 대신 빈 자리로 처리). 렌더 스레드를 절대 막지 않는다. */
public final class PlayerFaceFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static final long REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<String, Identifier> FACE_TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_ATTEMPT_MILLIS = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = new CopyOnWriteArraySet<>();
    private static final String STEVE_TEXTURE_URL =
            "http://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60f";

    private PlayerFaceFetcher() {
    }

    public static Identifier getFaceTexture(String playerName) {
        if (!AlbaPlatform.richFeaturesAvailable() || playerName == null || playerName.isBlank()) {
            return null;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        Identifier cached = FACE_TEXTURES.get(key);

        long now = System.currentTimeMillis();
        Long lastAttempt = LAST_ATTEMPT_MILLIS.get(key);
        boolean stale = lastAttempt == null || now - lastAttempt >= REFRESH_INTERVAL_MILLIS;
        if (stale && IN_FLIGHT.add(key)) {
            LAST_ATTEMPT_MILLIS.put(key, now);
            CompletableFuture.supplyAsync(() -> fetchSkinPng(playerName)).thenAccept(pngBytes -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    IN_FLIGHT.remove(key);
                    return;
                }
                client.execute(() -> {
                    try {
                        Identifier id = uploadFaceTexture(client, key, pngBytes);
                        if (id != null) {
                            FACE_TEXTURES.put(key, id);
                        }
                    } finally {
                        IN_FLIGHT.remove(key);
                    }
                });
            }).exceptionally(e -> {
                LOGGER.warn("[알바] 스킨 조회 실패 ({}): {}", playerName, e.getMessage());
                IN_FLIGHT.remove(key);
                return null;
            });
        }
        return cached;
    }

    private static byte[] fetchSkinPng(String playerName) {
        String skinUrl = resolveSkinUrl(playerName);
        return httpGetBytes(skinUrl != null ? skinUrl : STEVE_TEXTURE_URL);
    }

    private static String resolveSkinUrl(String playerName) {
        try {
            String uuid = fetchUuid(playerName);
            if (uuid == null) {
                return null;
            }
            String profileJson = httpGetString("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
            if (profileJson == null) {
                return null;
            }
            JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
            if (!profile.has("properties")) {
                return null;
            }
            for (JsonElement el : profile.getAsJsonArray("properties")) {
                JsonObject prop = el.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(prop.get("value").getAsString()));
                JsonObject texturesRoot = JsonParser.parseString(decoded).getAsJsonObject();
                JsonObject textures = texturesRoot.getAsJsonObject("textures");
                if (textures == null || !textures.has("SKIN")) {
                    continue;
                }
                return textures.getAsJsonObject("SKIN").get("url").getAsString();
            }
        } catch (Exception e) {
            LOGGER.warn("[알바] 스킨 URL 조회 실패 ({}): {}", playerName, e.getMessage());
        }
        return null;
    }

    private static String fetchUuid(String playerName) {
        try {
            String json = httpGetString("https://api.mojang.com/users/profiles/minecraft/" + playerName);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has("id") ? obj.get("id").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGetString(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? res.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] httpGetBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(8)).build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return res.statusCode() == 200 ? res.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Identifier uploadFaceTexture(MinecraftClient client, String key, byte[] pngBytes) {
        if (pngBytes == null) {
            return null;
        }
        // 8x8 얼굴+모자 레이어 자르기는 AlbaPlatform에 맡긴다 - NativeImage 색상 메서드 이름이
        // "1.21" 순정 버전만 다르게 불려서(getColor vs getColorArgb), 이 공용 파일(모든 버전에서
        // 컴파일됨)은 그 메서드를 직접 안 쓴다.
        try (NativeImage skin = NativeImage.read(new ByteArrayInputStream(pngBytes))) {
            if (skin.getWidth() < 64 || skin.getHeight() < 64) {
                return null;
            }
            return AlbaPlatform.uploadFaceTexture(client.getTextureManager(), key, skin);
        } catch (Exception e) {
            LOGGER.warn("[알바] 스킨 텍스처 생성 실패: {}", e.toString());
            return null;
        }
    }
}
