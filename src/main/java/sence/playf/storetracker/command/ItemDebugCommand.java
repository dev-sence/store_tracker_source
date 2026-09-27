package sence.playf.storetracker.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.tracker.ContainerSlots;

/**
 * 개발자 빌드 전용: 손에 든 아이템의 실제 데이터(기본값과 다른 부분)를 로그에 찍는다.
 * 겉보기 이름이 같은 "공용템"과 "개인템"이 실제로는 서로 다른 데이터(고유 태그 등)를 갖고 있는지
 * 확인해서, 이름이 아니라 그 데이터로 구분할 수 있는지 판단하기 위함.
 */
public final class ItemDebugCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("item_debug").executes(ItemDebugCommand::run)));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
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

        LOGGER.info("[item_debug] identityKey={}", ContainerSlots.identityKey(held));
        LOGGER.info("[item_debug] componentChanges={}", held.getComponentChanges());
        source.sendFeedback(Text.literal("§e[StoreTracker] 아이템 정보를 로그에 남겼습니다 (latest.log에서 item_debug 검색)."));
        return 1;
    }

    private ItemDebugCommand() {
    }
}
