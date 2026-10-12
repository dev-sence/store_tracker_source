package sence.playf.storetracker.alba;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 섬 서버의 기존 "알바" 명령 플러그인(우리 모드와 무관)이 보내는 신호를 감지한다 - 명령어 자체를
 * 가로채거나 취소하지 않고(플러그인은 평소대로 동작), 똑같은 걸 우리 서버에도 보고만 한다. 두 경로로
 * 감지한다: (1) 본인이 직접 "/알바 고용 <이름>" 명령을 쳤을 때, (2) 플러그인의 GUI 등 명령어가 아닌
 * 방식으로 고용됐을 때도 뜨는 서버 브로드캐스트 채팅("{고용주}님이 {알바}님을 알바생으로 추가했어요").
 * 두 경로가 같은 고용을 동시에 잡을 수 있어서 짧은 시간창으로 중복을 거른다. 서버도 동일 쌍의
 * 활성 고용이 있으면 또 만들지 않으므로(멱등 처리), 여러 클라이언트가 같은 채팅을 보고 동시에
 * 보고해도 최초 한 번만 반영된다.
 */
public final class AlbaHireDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    private static final Pattern HIRE_COMMAND = Pattern.compile("^알바\\s+고용\\s+(\\S+)\\s*$");
    private static final Pattern FIRE_COMMAND = Pattern.compile("^알바\\s+해고\\s+(\\S+)\\s*$");
    private static final Pattern HIRE_BROADCAST =
            Pattern.compile("(\\S+)님이\\s+(\\S+)님을\\s+알바생으로\\s+추가했어요");
    private static final String FIRE_KEYWORD = "해고";

    private static final Map<String, Long> recentHireEvents = new ConcurrentHashMap<>();
    private static final long HIRE_DEDUP_WINDOW_MILLIS = 3_000L;

    private AlbaHireDetector() {
    }

    public static void register() {
        ClientSendMessageEvents.COMMAND.register(AlbaHireDetector::onCommandSent);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onBroadcastMessage(message));
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onBroadcastMessage(message));
    }

    private static void onCommandSent(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || command == null) {
            return;
        }
        String trimmed = command.trim();
        String employer = client.player.getName().getString();

        Matcher hireMatcher = HIRE_COMMAND.matcher(trimmed);
        if (hireMatcher.matches()) {
            handleHireDetected(employer, hireMatcher.group(1));
            return;
        }

        Matcher fireMatcher = FIRE_COMMAND.matcher(trimmed);
        if (fireMatcher.matches()) {
            String employee = fireMatcher.group(1);
            LOGGER.info("[알바] 해고 감지(명령어): {} -> {}", employer, employee);
            AlbaTracker.getBridge().fire(employer, employee);
            AlbaTracker.forcePollNow();
        }
    }

    private static void onBroadcastMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || message == null) {
            return;
        }
        String text = message.getString();
        String localName = client.player.getName().getString();
        if (!containsIgnoreCase(text, localName)) {
            return;
        }

        Matcher hireMatcher = HIRE_BROADCAST.matcher(text);
        if (hireMatcher.find()) {
            String broadcastEmployer = hireMatcher.group(1);
            String broadcastEmployee = hireMatcher.group(2);
            if (broadcastEmployer.equalsIgnoreCase(localName)) {
                handleHireDetected(localName, broadcastEmployee);
            }
            return;
        }

        if (!text.contains(FIRE_KEYWORD)) {
            return;
        }
        for (AlbaEntry entry : AlbaTracker.getCachedHires()) {
            if (!entry.employer().equalsIgnoreCase(localName)) {
                continue;
            }
            if (containsIgnoreCase(text, entry.employee())) {
                LOGGER.info("[알바] 해고 감지(메시지): {} -> {}", localName, entry.employee());
                AlbaTracker.getBridge().fire(localName, entry.employee());
                AlbaTracker.forcePollNow();
                return;
            }
        }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    /** 명령어 경로/메시지 경로가 같은 고용을 중복 보고하지 않게 하고, 둘 다 같은 선택 GUI를 두 번
     * 띄우지 않게 한다. 마인크래프트 닉네임은 대소문자를 구분하지 않는다. */
    private static void handleHireDetected(String employer, String employee) {
        String key = employer.toLowerCase() + "|" + employee.toLowerCase();
        long now = System.currentTimeMillis();
        Long last = recentHireEvents.put(key, now);
        if (last != null && now - last < HIRE_DEDUP_WINDOW_MILLIS) {
            return;
        }
        LOGGER.info("[알바] 고용 감지: {} -> {}", employer, employee);
        AlbaTracker.getBridge().hire(employer, employee);
        AlbaTracker.forcePollNow();
        AlbaGuiScreen.openForNewHire(employer, employee);
    }
}
