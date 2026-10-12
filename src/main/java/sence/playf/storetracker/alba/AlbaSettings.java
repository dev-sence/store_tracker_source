package sence.playf.storetracker.alba;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 알바 HUD/GUI 설정 - ModConfig와 같은 패턴(Gson 직렬화, config 폴더에 json으로 저장)으로 따로
 * 관리한다 (알바 기능만의 설정이라 본체 ModConfig와 섞지 않음). */
public class AlbaSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("sence_storetracker_alba.json");

    private static AlbaSettings instance;

    public int hudX = 8;
    public int hudY = 8;
    public boolean hudVisible = true;
    public String[] zoneLabels = {
            AlbaZone.defaultLabel(AlbaZone.PINK), AlbaZone.defaultLabel(AlbaZone.YELLOW),
            AlbaZone.defaultLabel(AlbaZone.PURPLE), AlbaZone.defaultLabel(AlbaZone.BLUE),
    };

    public static synchronized AlbaSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public String zoneLabel(AlbaZone zone) {
        int i = zone.ordinal();
        if (zoneLabels != null && i < zoneLabels.length && zoneLabels[i] != null) {
            return zoneLabels[i];
        }
        return AlbaZone.defaultLabel(zone);
    }

    public void nudgePosition(int dx, int dy) {
        hudX = Math.max(0, hudX + dx);
        hudY = Math.max(0, hudY + dy);
        save();
    }

    private static AlbaSettings load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                AlbaSettings loaded = GSON.fromJson(json, AlbaSettings.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                LOGGER.warn("알바 설정 파일을 읽지 못했습니다. 기본값을 사용합니다.", e);
            }
        }
        AlbaSettings defaults = new AlbaSettings();
        defaults.save();
        return defaults;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("알바 설정 파일을 저장하지 못했습니다.", e);
        }
    }
}
