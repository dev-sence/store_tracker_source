package sence.playf.storetracker.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import sence.playf.storetracker.build.BuildInfo;

/** 모든 빌드 공통: 지금 실행 중인 모드의 버전/빌드 종류를 확인한다. */
public final class VersionCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("store_track")
                        .then(ClientCommandManager.literal("ver").executes(VersionCommand::run))));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal(
                "§e[StoreTracker] 버전: " + BuildInfo.MOD_VERSION + " (" + BuildInfo.BUILD_TYPE + ")"));
        return 1;
    }

    private VersionCommand() {
    }
}
