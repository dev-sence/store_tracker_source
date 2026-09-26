package sence.playf.storetracker.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.registry.ChestRegistry;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

/** 개발자 빌드 전용: 조준 중인 상자를 "공용템 상자"로 서버에 등록한다. */
public final class ChestAddCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("chest_add").executes(ChestAddCommand::run)));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();

        HitResult target = client.crosshairTarget;
        if (target == null || target.getType() != HitResult.Type.BLOCK || !(target instanceof BlockHitResult blockHit)) {
            source.sendError(Text.literal("상자를 조준한 상태에서 실행하세요."));
            return 0;
        }
        if (client.world == null) {
            source.sendError(Text.literal("월드 정보를 확인할 수 없습니다."));
            return 0;
        }

        BlockPos pos = blockHit.getBlockPos();
        String dimension = client.world.getRegistryKey().getValue().toString();
        String mapKey = MapKey.current(client);
        String username = client.getSession().getUsername();

        source.sendFeedback(Text.literal("§e[StoreTracker] 상자 등록 중..."));

        Async.run(() -> {
            SecureChannel channel = new SecureChannel(BuildInfo.APP_SECRET);
            JsonObject body = new JsonObject();
            body.addProperty("map_key", mapKey);
            body.addProperty("dimension", dimension);
            body.addProperty("x", pos.getX());
            body.addProperty("y", pos.getY());
            body.addProperty("z", pos.getZ());
            body.addProperty("label", "공용템 상자");
            body.addProperty("username", username);

            ApiClient.Result result = ApiClient.postEncrypted(
                    BuildInfo.API_BASE_URL, "/api/chests", channel, body, BuildInfo.ADMIN_TOKEN);

            // refresh()도 네트워크 호출이라 렌더 스레드가 아니라 여기(백그라운드)에서 끝내둔다.
            if (result.isSuccess()) {
                ChestRegistry.refresh(mapKey);
            }

            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                if (result.isSuccess()) {
                    client.player.sendMessage(Text.literal("§a[StoreTracker] 상자 등록 완료: " + dimension
                            + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), false);
                } else {
                    client.player.sendMessage(Text.literal(
                            "§c[StoreTracker] 상자 등록 실패 (status=" + result.statusCode() + ")"), false);
                }
            });
        });

        return 1;
    }

    private ChestAddCommand() {
    }
}
