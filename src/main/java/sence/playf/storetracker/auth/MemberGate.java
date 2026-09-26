package sence.playf.storetracker.auth;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sence.playf.storetracker.build.BuildInfo;
import sence.playf.storetracker.crypto.SecureChannel;
import sence.playf.storetracker.net.ApiClient;
import sence.playf.storetracker.update.UpdateChecker;
import sence.playf.storetracker.util.Async;

/** 서버 접속마다 업데이트 확인 -> 멤버십 확인을 수행하고, 결과에 따라 추적 기능을 켜고 끈다. */
public final class MemberGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");
    private static volatile boolean authorized = false;
    private static SecureChannel channel;

    public static boolean isAuthorized() {
        return authorized;
    }

    /** 렌더/네트워크 스레드를 막지 않도록 백그라운드에서 실행된다. */
    public static void runGateFlowAsync() {
        if (!BuildInfo.MEMBER_GATE_ENABLED) {
            // dev 빌드: 항상 통과 (로컬 테스트 편의)
            authorized = true;
            return;
        }

        authorized = false;
        Async.run(MemberGate::runGateFlow);
    }

    private static void runGateFlow() {
        if (BuildInfo.UPDATE_CHECK_ENABLED) {
            UpdateChecker.checkForUpdate().ifPresent(info -> notifyPlayer(
                    "§e[StoreTracker] 새 버전이 있습니다: " + info.latestVersion() + " - " + info.releaseUrl()));
        }

        String username = MinecraftClient.getInstance().getSession().getUsername();
        JsonObject body = new JsonObject();
        body.addProperty("username", username);

        ApiClient.Result result = ApiClient.postEncrypted(
                BuildInfo.API_BASE_URL, "/api/check-member", channel(), body);

        if (result.isOk()) {
            authorized = true;
            LOGGER.info("멤버 인증 성공: {}", username);
        } else if (result.isNotFound()) {
            authorized = false;
            notifyPlayer("§c[StoreTracker] 등록되지 않은 사용자입니다. Discord :@sence1012 로 문의해주세요.");
        } else {
            authorized = false;
            LOGGER.warn("멤버 인증 확인 실패 (status={}, networkError={})", result.statusCode(), result.networkError());
            notifyPlayer("§c[StoreTracker] 서버와 연결을 실패했습니다. Discord :@sence1012 로 연락주세요.");
        }
    }

    private static synchronized SecureChannel channel() {
        if (channel == null) {
            channel = new SecureChannel(BuildInfo.APP_SECRET);
        }
        return channel;
    }

    private static void notifyPlayer(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(message), false);
            }
        });
    }

    private MemberGate() {
    }
}
