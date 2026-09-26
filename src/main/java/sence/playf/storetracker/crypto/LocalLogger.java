package sence.playf.storetracker.crypto;

import com.google.gson.JsonElement;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.build.BuildInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * 네트워크 전송 성공 여부와 무관하게 입출고 이벤트를 로컬에 1차 기록한다.
 * 한 줄에 하나씩 base64(AES-GCM) 페이로드를 append한다.
 */
public final class LocalLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static final Path LOG_DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("sence_storetracker").resolve("logs");

    private static SecureChannel channel;

    private static synchronized SecureChannel channel() {
        if (channel == null) {
            channel = new SecureChannel(BuildInfo.APP_SECRET);
        }
        return channel;
    }

    public static synchronized void log(JsonElement event) {
        try {
            Files.createDirectories(LOG_DIR);
            Path file = LOG_DIR.resolve("events-" + LocalDate.now() + ".log.enc");
            String line = channel().encryptJson(event);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("로컬 암호화 로그 기록 실패", e);
        }
    }

    private LocalLogger() {
    }
}
