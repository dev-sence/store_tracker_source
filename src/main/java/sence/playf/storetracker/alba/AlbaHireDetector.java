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

    // 전엔 .matches()로 "알바 고용 <이름>"과 문자 그대로 끝까지 일치해야만 잡았는데, 플러그인이
    // 뒤에 추가 인자/옵션을 더 붙이거나 끝에 공백이 더 있으면 그 즉시 통째로 안 잡혔다(브로드캐스트
    // 쪽 패턴은 원래 find()라 안 이랬음 - 명령어 쪽만 유독 깐깐했던 비일관성). find()로 바꾸고
    // 끝 앵커(\s*$)를 떼서 "알바 고용 <이름>"으로 시작하기만 하면 뒤에 뭐가 더 붙어도 잡히게 함.
    private static final Pattern HIRE_COMMAND = Pattern.compile("^알바\\s+고용\\s+(\\S+)");
    private static final Pattern FIRE_COMMAND = Pattern.compile("^알바\\s+해고\\s+(\\S+)");
    private static final Pattern HIRE_BROADCAST =
            Pattern.compile("(\\S+)님이\\s+(\\S+)님을\\s+알바생으로\\s+추가했어요");
    // "{해고한 사람}님이 알바생 {알바}님을 해고했어요." - 메시지에서 해고한 사람을 직접 뽑아내므로,
    // 고용주 본인이 아니라 그걸 본 아무 클라이언트나(심지어 고용주가 접속 안 해 있어도) 신고할 수 있다.
    private static final Pattern FIRE_BROADCAST =
            Pattern.compile("(\\S+)님이\\s+알바생\\s+(\\S+)님을\\s+해고했어요");

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
        if (hireMatcher.find()) {
            handleHireDetected(employer, hireMatcher.group(1));
            return;
        }

        Matcher fireMatcher = FIRE_COMMAND.matcher(trimmed);
        if (fireMatcher.find()) {
            String employee = fireMatcher.group(1);
            LOGGER.info("[알바] 해고 감지(명령어): {} -> {}", employer, employee);
            AlbaTracker.getBridge().fire(employer, employee);
            AlbaTracker.forcePollNow();
            return;
        }

        // "알바"로 시작하는데 위 두 패턴 어디에도 안 걸리면 - 플러그인 명령 형식이 예상과 달랐다는
        // 뜻이라 로그에 남겨둔다(다음에 똑같은 문제가 또 보고되면 이 줄로 바로 원인을 알 수 있게).
        if (trimmed.startsWith("알바")) {
            LOGGER.info("[알바] 인식 안 되는 명령어 형식: \"{}\"", trimmed);
        }
    }

    private static void onBroadcastMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || message == null) {
            return;
        }
        String text = message.getString();
        String localName = client.player.getName().getString();

        // 고용은 고용주 본인 화면에서만 처리한다 (고용 직후 본인 작업선택 GUI를 띄워야 하므로).
        Matcher hireMatcher = HIRE_BROADCAST.matcher(text);
        if (hireMatcher.find()) {
            String broadcastEmployer = hireMatcher.group(1);
            String broadcastEmployee = hireMatcher.group(2);
            if (broadcastEmployer.equalsIgnoreCase(localName)) {
                handleHireDetected(localName, broadcastEmployee);
            }
            return;
        }

        // 해고는 메시지에 누가 해고했는지 그대로 적혀 있으므로, 고용주가 아니거나 접속 중이 아니어도
        // 그 메시지를 본 아무 클라이언트나(모드 쓰는 사람 누구든) 신고할 수 있다.
        Matcher fireMatcher = FIRE_BROADCAST.matcher(text);
        if (fireMatcher.find()) {
            String firer = fireMatcher.group(1);
            String employee = fireMatcher.group(2);
            LOGGER.info("[알바] 해고 감지(메시지, 목격자={}): {} -> {}", localName, firer, employee);
            AlbaTracker.getBridge().fire(firer, employee);
            AlbaTracker.forcePollNow();
        }
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
