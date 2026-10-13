package sence.playf.storetracker.alba;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 서버의 "지금 일하는 알바" 목록을 로컬에 캐시해두고 주기적으로 갱신한다 (HUD/GUI/자동완성이
 * 이 캐시만 읽으면 되게). 고용/해고/작업설정 직후엔 다음 폴링을 기다리지 않고 바로 다시 받아온다. */
public final class AlbaTracker {
    private static final AlbaBridge BRIDGE = new AlbaBridge();
    private static volatile List<AlbaEntry> cached = List.of();
    private static final AtomicBoolean polling = new AtomicBoolean(false);

    public static AlbaBridge getBridge() {
        return BRIDGE;
    }

    public static List<AlbaEntry> getCachedHires() {
        return cached;
    }

    public static void pollNow() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            BRIDGE.fetchStatus(entries -> {
                cached = entries;
                polling.set(false);
            });
        } catch (Exception e) {
            // fetchStatus 자체가 콜백을 부르기도 전에 던지면 polling이 true로 영영 멈춰서 이후의
            // 모든 폴링이 조용히 무시된다(HUD가 그 순간에 박제됨) - 그런 일이 없게 AlbaBridge
            // 쪽에서 이미 내부적으로 다 잡지만(try-catch), 혹시 몰라 여기도 한 번 더 막아둔다.
            polling.set(false);
            throw e;
        }
    }

    /** 고용/해고/작업 변경 직후 호출 - 다음 주기 폴링을 기다리지 않고 바로 최신화한다. */
    public static void forcePollNow() {
        pollNow();
    }

    private AlbaTracker() {
    }
}
