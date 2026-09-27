package sence.playf.storetracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.auth.MemberGate;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.command.ChestAddCommand;
import sence.playf.storetracker.command.DevToggleCommand;
import sence.playf.storetracker.command.ItemDebugCommand;
import sence.playf.storetracker.command.ItemResetCommand;
import sence.playf.storetracker.command.VersionCommand;
import sence.playf.storetracker.config.ModConfig;
import sence.playf.storetracker.display.PublicItemDisplay;
import sence.playf.storetracker.registry.ChestRegistry;
import sence.playf.storetracker.registry.FeatureToggles;
import sence.playf.storetracker.registry.HeldItemLedger;
import sence.playf.storetracker.tracker.ContainerTracker;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

public class StoreTrackerClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    @Override
    public void onInitializeClient() {
        ModConfig.get();
        LOGGER.info("Sence_StoreTracker 초기화 (buildType={}, version={}, apiBaseUrl={})",
                BuildInfo.BUILD_TYPE, BuildInfo.MOD_VERSION, BuildInfo.API_BASE_URL);

        ContainerTracker.register();
        PublicItemDisplay.register();
        VersionCommand.register();

        if (BuildInfo.isDevBuild()) {
            ChestAddCommand.register();
            ItemResetCommand.register();
            ItemDebugCommand.register();
            DevToggleCommand.register();
        }

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String mapKey = MapKey.current(client);
            String username = client.getSession().getUsername();
            Async.run(() -> {
                ChestRegistry.refresh(mapKey);
                HeldItemLedger.fetch(mapKey, username);
                FeatureToggles.fetch(mapKey);
            });

            ModConfig config = ModConfig.get();
            String currentAddress = client.getCurrentServerEntry() != null
                    ? client.getCurrentServerEntry().address
                    : "";
            if (config.serverAddress.isBlank() || config.serverAddress.equalsIgnoreCase(currentAddress)) {
                MemberGate.runGateFlowAsync();
            }
        });

        // 상자 상호작용 없이도 보유 장부가 계속 최신으로 유지되게 주기적으로 다시 받아온다.
        int[] tickCounter = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            if (++tickCounter[0] < 1200) { // 20틱/초 * 60초
                return;
            }
            tickCounter[0] = 0;
            String mapKey = MapKey.current(client);
            String username = client.getSession().getUsername();
            Async.run(() -> {
                HeldItemLedger.fetch(mapKey, username);
                FeatureToggles.fetch(mapKey);
            });
        });
    }
}
