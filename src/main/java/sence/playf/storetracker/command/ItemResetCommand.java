package sence.playf.storetracker.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.registry.HeldItemLedger;
import sence.playf.storetracker.tracker.ContainerSlots;
import sence.playf.storetracker.util.Async;
import sence.playf.storetracker.util.MapKey;

import java.util.Set;

/**
 * 개발자 빌드 전용: 내 "보유 공용템 장부"를 강제로 0으로 되돌린다. 상자 안의 진짜 공용템/다른 플레이어
 * 장부에는 영향 없음 - 오직 나 자신이 지금 들고 있다고 인정되는 개수만 리셋한다.
 */
public final class ItemResetCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("item_reset")
                        .then(ClientCommandManager.literal("hand").executes(ItemResetCommand::resetHand))
                        .then(ClientCommandManager.literal("all").executes(ItemResetCommand::resetAll))));
    }

    private static int resetHand(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        if (client.player == null) {
            return 0;
        }

        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) {
            source.sendError(Text.literal("손에 아이템을 들고 실행하세요."));
            return 0;
        }

        submit(client, source, Set.of(ContainerSlots.identityKey(held)));
        return 1;
    }

    private static int resetAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        if (client.player == null) {
            return 0;
        }

        submit(client, source, null);
        return 1;
    }

    /** identityKeys가 null이면 이 플레이어의 보유 장부 전체를 리셋한다. */
    private static void submit(MinecraftClient client, FabricClientCommandSource source, Set<String> identityKeys) {
        String mapKey = MapKey.current(client);
        String username = client.getSession().getUsername();
        source.sendFeedback(Text.literal("§e[StoreTracker] 보유 장부 리셋 중..."));

        Async.run(() -> {
            SecureChannel channel = new SecureChannel(BuildInfo.APP_SECRET);
            JsonObject body = new JsonObject();
            body.addProperty("map_key", mapKey);
            body.addProperty("username", username);
            if (identityKeys != null) {
                JsonArray array = new JsonArray();
                identityKeys.forEach(array::add);
                body.add("item_ids", array);
            }

            ApiClient.Result result = ApiClient.postEncrypted(
                    BuildInfo.API_BASE_URL, "/api/held-items/reset", channel, body, BuildInfo.ADMIN_TOKEN);

            if (result.isSuccess()) {
                HeldItemLedger.fetch(mapKey, username);
            }

            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                if (result.isSuccess()) {
                    client.player.sendMessage(Text.literal("§a[StoreTracker] 보유 장부 리셋 완료"), false);
                } else {
                    client.player.sendMessage(Text.literal(
                            "§c[StoreTracker] 리셋 실패 (status=" + result.statusCode() + ")"), false);
                }
            });
        });
    }

    private ItemResetCommand() {
    }
}
