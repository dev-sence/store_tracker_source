package sence.playf.storetracker.update;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 새 버전을 발견하면 그 자리에서 jar를 받아와 적용까지 해준다 - 링크만 던져주고 사람이 직접
 * 내려받아 mods 폴더를 바꿔치기하던 예전 방식 대신이다.
 *
 * 다만 지금 실행 중인 jar 파일은 JVM이 열어서 쓰고 있어서 그 자리에서 바로 덮어쓸 수 없다
 * (특히 Windows는 열려 있는 파일을 다른 파일로 교체하는 것 자체가 막혀 있다). 그래서:
 *   1. 새 jar를 mods 폴더 밖의 임시 위치에 받아둔다.
 *   2. JVM이 완전히 종료되는 시점(shutdown hook)에 원래 jar 파일을 지우고 새 파일을 그 자리로 옮긴다.
 * 즉 "지금 당장 핫스왑"이 아니라 "다음에 게임을 다시 켜면 새 버전"이다. 그 사이 강제 종료 등으로
 * 교체가 실패해도 그냥 다음 실행 때 다시 받아서 재시도하면 되니 안전하다(기존 jar를 건드리는 시점은
 * 새 파일이 100% 온전히 받아진 뒤뿐이라, 실패해도 기존 mods 폴더가 깨지는 일은 없다).
 */
public final class AutoUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static final String MOD_ID = "sence_storetracker";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub 릴리스 asset 링크는 실제 저장소로 리다이렉트된다.
            .build();

    /** 새 버전을 찾으면 받아서 다음 재시작 때 적용되게 준비하고, 그 결과를 채팅으로 안내한다. */
    public static void applyUpdate(UpdateChecker.UpdateInfo info) {
        if (!info.hasDownloadableAsset()) {
            notifyPlayer("§e[StoreTracker] 새 버전이 있습니다: " + info.latestVersion()
                    + " - 자동 설치 대상 파일을 찾지 못해 수동으로 받아주세요: " + info.releaseUrl());
            return;
        }

        Optional<Path> currentJar = currentModJarPath();
        if (currentJar.isEmpty()) {
            notifyPlayer("§e[StoreTracker] 새 버전이 있습니다: " + info.latestVersion() + " - " + info.releaseUrl());
            return;
        }

        Path stagingDir = FabricLoader.getInstance().getGameDir().resolve(".sence_storetracker_update");
        Path pendingJar = stagingDir.resolve("pending.jar");
        Path pendingMarker = stagingDir.resolve("pending.version");

        try {
            if (Files.exists(pendingMarker)
                    && info.latestVersion().equals(Files.readString(pendingMarker).strip())
                    && Files.exists(pendingJar) && Files.size(pendingJar) > 0) {
                // 이미 같은 버전을 받아서 재시작만 기다리고 있다.
                scheduleSwap(currentJar.get(), pendingJar);
                notifyPlayer("§a[StoreTracker] 새 버전(" + info.latestVersion()
                        + ")이 이미 준비되어 있습니다 - 게임을 재시작하면 적용됩니다.");
                return;
            }

            Files.createDirectories(stagingDir);
            byte[] jarBytes = download(info.assetDownloadUrl());
            if (jarBytes == null || jarBytes.length == 0) {
                notifyPlayer("§c[StoreTracker] 새 버전(" + info.latestVersion()
                        + ") 다운로드에 실패했습니다. 수동으로 받아주세요: " + info.releaseUrl());
                return;
            }

            Files.write(pendingJar, jarBytes);
            Files.writeString(pendingMarker, info.latestVersion());

            scheduleSwap(currentJar.get(), pendingJar);
            notifyPlayer("§a[StoreTracker] 새 버전(" + info.latestVersion()
                    + ") 다운로드 완료 - 게임을 재시작하면 적용됩니다.");
        } catch (IOException e) {
            LOGGER.warn("자동 업데이트 준비 실패", e);
            notifyPlayer("§c[StoreTracker] 새 버전(" + info.latestVersion()
                    + ") 자동 설치에 실패했습니다. 수동으로 받아주세요: " + info.releaseUrl());
        }
    }

    private static byte[] download(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOGGER.warn("업데이트 파일 다운로드 실패 (status={})", response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Optional<Path> currentModJarPath() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getOrigin().getPaths())
                .filter(paths -> !paths.isEmpty())
                .map(List::getFirst);
    }

    /** JVM이 완전히 끝날 때 원래 jar를 새 jar로 바꿔치기한다 - 실행 중엔 파일이 잠겨 있어서 지금은 못 한다. */
    private static void scheduleSwap(Path currentJar, Path pendingJar) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.move(pendingJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // 실패해도 pendingJar는 그대로 남아있으니 다음 실행 때 다시 시도된다.
                LOGGER.warn("업데이트 파일 교체 실패 - 다음 실행 때 다시 시도합니다", e);
            }
        }, "sence-storetracker-update-swap"));
    }

    private static void notifyPlayer(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(message), false);
            }
        });
    }

    private AutoUpdater() {
    }
}
