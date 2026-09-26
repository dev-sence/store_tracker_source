package sence.playf.storetracker.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sence.playf.storetracker.build.BuildInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public final class UpdateChecker {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record UpdateInfo(String latestVersion, String releaseUrl) {
    }

    /** 최신 릴리스가 현재 버전보다 새 것이면 정보를 반환한다. 네트워크 실패/미설정 시 조용히 비어있음을 반환. */
    public static Optional<UpdateInfo> checkForUpdate() {
        if (BuildInfo.GITHUB_REPO.isBlank() || BuildInfo.GITHUB_REPO.startsWith("TODO/")) {
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + BuildInfo.GITHUB_REPO + "/releases/latest"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String tag = json.get("tag_name").getAsString();
            String latestVersion = tag.startsWith("v") ? tag.substring(1) : tag;
            String releaseUrl = json.has("html_url") ? json.get("html_url").getAsString() : "";

            if (isNewer(latestVersion, BuildInfo.MOD_VERSION)) {
                return Optional.of(new UpdateInfo(latestVersion, releaseUrl));
            }
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    static boolean isNewer(String latest, String current) {
        int[] a = parseVersion(latest);
        int[] b = parseVersion(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) {
                return ai > bi;
            }
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        // "1.2.3-SNAPSHOT" 같은 접미사는 숫자 비교에서 제외
        String[] parts = version.split("-")[0].split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private UpdateChecker() {
    }
}
