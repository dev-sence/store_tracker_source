package sence.playf.storetracker.alba;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

/** "/알바설정"으로 알바 설정 화면을 연다. 1.21.9부터 KeyBinding.Category의 타입이 바뀌어서
 * 단축키 하나로 전 버전을 지원할 수 없었다 - 커맨드는 전 버전에서 동일하게 동작해서 이쪽을 쓴다. */
public final class AlbaSettingsCommand {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("알바설정").executes(AlbaSettingsCommand::run)));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.currentScreen == null) {
                client.setScreen(new AlbaEditScreen());
            }
        });
        return 1;
    }

    private AlbaSettingsCommand() {
    }
}
