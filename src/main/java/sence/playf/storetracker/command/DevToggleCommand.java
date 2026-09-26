package sence.playf.storetracker.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.registry.FeatureToggles;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

/**
 * 개발자 빌드 전용: 각 기능을 껐다 켰다 테스트할 수 있는 명령어. 서버(맵별)에 상태를 저장하므로
 * 이 맵에 접속한 모든 유저(배포용 빌드 포함)에게 곧 반영된다 - 이 클라이언트에서만 켜지는 게 아니다.
 */
public final class DevToggleCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("dev_toggle")
                        .executes(DevToggleCommand::list)
                        .then(ClientCommandManager.literal("label").executes(ctx -> toggle(ctx, "label")))
                        .then(ClientCommandManager.literal("tag").executes(ctx -> toggle(ctx, "tag")))
                        .then(ClientCommandManager.literal("passthrough").executes(ctx -> toggle(ctx, "passthrough")))
                        .then(ClientCommandManager.literal("chestlog").executes(ctx -> toggle(ctx, "chestlog")))));
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        String mapKey = MapKey.current(client);

        source.sendFeedback(Text.literal("§e[StoreTracker] 서버에서 최신 기능 상태를 가져오는 중..."));

        Async.run(() -> {
            FeatureToggles.fetch(mapKey);
            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                client.player.sendMessage(Text.literal(
                        "§e[StoreTracker] 기능 상태 (/dev_toggle <이름>으로 전환, 모든 유저에게 적용됨)\n"
                                + "  label (상자 제목 강조): " + state(FeatureToggles.labelOverlay()) + "\n"
                                + "  tag ([공용템] 표시): " + state(FeatureToggles.publicTag()) + "\n"
                                + "  passthrough (경유 상자 추적): " + state(FeatureToggles.passthroughTracking()) + "\n"
                                + "  chestlog (열림/닫힘 로그): " + state(FeatureToggles.chestLog())), false);
            });
        });
        return 1;
    }

    private static int toggle(CommandContext<FabricClientCommandSource> context, String key) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        String mapKey = MapKey.current(client);
        String username = client.getSession().getUsername();
        String serverKey = toServerKey(key);

        source.sendFeedback(Text.literal("§e[StoreTracker] " + key + " 전환 중..."));

        Async.run(() -> {
            SecureChannel channel = new SecureChannel(BuildInfo.APP_SECRET);
            JsonObject body = new JsonObject();
            body.addProperty("map_key", mapKey);
            body.addProperty("key", serverKey);
            body.addProperty("username", username);

            ApiClient.Result result = ApiClient.postEncrypted(
                    BuildInfo.API_BASE_URL, "/api/feature-toggles/toggle", channel, body, BuildInfo.ADMIN_TOKEN);

            if (result.isSuccess()) {
                // 서버가 이미 최신 상태를 알고 있으니, 이 클라이언트도 곧바로 그 값으로 맞춘다
                // (다른 유저들은 60초 주기 재조회 또는 다음 상자 열람 시 자연스럽게 따라온다).
                FeatureToggles.fetch(mapKey);
            }

            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                if (result.isSuccess()) {
                    client.player.sendMessage(Text.literal(
                            "§a[StoreTracker] " + key + " -> " + state(currentValue(key))
                                    + " §7(모든 유저에게 곧 적용됩니다)"), false);
                } else {
                    client.player.sendMessage(Text.literal(
                            "§c[StoreTracker] 전환 실패 (status=" + result.statusCode() + ")"), false);
                }
            });
        });
        return 1;
    }

    private static String toServerKey(String key) {
        return switch (key) {
            case "label" -> "label_overlay";
            case "tag" -> "public_tag";
            case "passthrough" -> "passthrough_tracking";
            case "chestlog" -> "chest_log";
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static boolean currentValue(String key) {
        return switch (key) {
            case "label" -> FeatureToggles.labelOverlay();
            case "tag" -> FeatureToggles.publicTag();
            case "passthrough" -> FeatureToggles.passthroughTracking();
            case "chestlog" -> FeatureToggles.chestLog();
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static String state(boolean value) {
        return value ? "§aON§r" : "§cOFF§r";
    }

    private DevToggleCommand() {
    }
}
