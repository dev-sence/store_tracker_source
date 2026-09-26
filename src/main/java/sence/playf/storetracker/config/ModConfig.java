package sence.playf.storetracker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("sence_storetracker.json");

    private static ModConfig instance;

    /** 이 서버 주소에 접속했을 때만 멤버 게이트/추적 기능이 동작한다. 비워두면 모든 서버에서 동작. */
    public String serverAddress = "";
    public List<TrackedPosition> trackedPositions = new ArrayList<>();
    /** null이면 BuildInfo.API_BASE_URL(빌드 시점 값)을 사용. */
    public String apiBaseUrlOverride = null;

    public static synchronized ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                LOGGER.warn("설정 파일을 읽지 못했습니다. 기본값을 사용합니다.", e);
            }
        }
        ModConfig defaults = new ModConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("설정 파일을 저장하지 못했습니다.", e);
        }
    }
}
