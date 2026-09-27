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
 * (특히 Windows는 열려 있는 파일을 다른 파일로 교체하는 것 자체가 막혀 있다). 그래서 새 jar를
 * mods 폴더 밖의 임시 위치에 받아두고, 실제 파일 교체는 이 게임 프로세스와 독립적인 별도 프로세스가
 * 맡는다(Windows에선 PowerShell 스크립트를 백그라운드로 하나 띄운다). shutdown hook은 JVM이
 * "종료 중"인 시점에 실행되는 거라, 그 순간에도 클래스로더가 아직 jar 파일 핸들을 붙들고 있어서
 * 실제로 해보면 항상 파일 잠금 실패로 끝난다(실제 테스트에서 확인됨) - 그래서 그 방식은 안 쓴다.
 * 대신 띄워둔 별도 프로세스가 게임이 완전히 종료돼서 OS가 파일 잠금을 풀 때까지 몇 초 간격으로
 * 계속 재시도하다가, 풀리는 순간 옮겨치기한다. 즉 "지금 당장 핫스왑"이 아니라 "게임을 끄면 그 직후
 * 적용되고, 다음에 다시 켜면 새 버전"이다. 그 사이 문제가 생겨도 pendingJar는 그대로 남아있으니
 * 다음 실행 때 다시 시도하면 되므로 안전하다.
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

    /**
     * 지금 이 게임 프로세스와 무관하게, 게임이 완전히 종료돼서 jar 파일 잠금이 풀리는 순간
     * 새 파일로 바꿔치기하는 역할만 하는 별도 프로세스를 하나 띄운다. 이 프로세스는 게임을
     * 껐다 켜는 것과 무관하게 백그라운드에서 계속 재시도하다가, 성공하면 스스로 종료된다.
     */
    private static void scheduleSwap(Path currentJar, Path pendingJar) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                scheduleSwapWindows(currentJar, pendingJar);
            } else {
                // Windows가 아니면 열려 있는 파일도 그냥 옮겨치기(rename)가 되는 경우가 대부분이라
                // 바로 시도해보고, 안 되면(드묾) 다음 실행 때 재시도되게 조용히 넘어간다.
                try {
                    Files.move(pendingJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOGGER.warn("업데이트 파일 교체 실패 - 다음 실행 때 다시 시도합니다", e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("업데이트 교체용 도우미 프로세스를 띄우지 못했습니다 - 다음 실행 때 다시 시도합니다", e);
        }
    }

    private static void scheduleSwapWindows(Path currentJar, Path pendingJar) throws IOException {
        // 최대 30분(1800 * 1초) 동안 1초 간격으로 재시도한다 - 그 사이 게임이 완전히 꺼지면
        // Windows가 파일 잠금을 풀어서 바로 성공한다. 따옴표는 PowerShell 작은따옴표 이스케이프('').
        String pendingStr = pendingJar.toAbsolutePath().toString().replace("'", "''");
        String currentStr = currentJar.toAbsolutePath().toString().replace("'", "''");
        String script = "$p = '" + pendingStr + "'; $c = '" + currentStr + "'; "
                + "for ($i = 0; $i -lt 1800; $i++) { "
                + "try { Move-Item -Force -LiteralPath $p -Destination $c -ErrorAction Stop; exit 0 } "
                + "catch { Start-Sleep -Seconds 1 } }";

        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();
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
