package sence.playf.storetracker.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 네트워크/파일 IO를 렌더 스레드 밖에서 처리하기 위한 공용 백그라운드 실행기. */
public final class Async {
    private static final Logger LOGGER = LoggerFactory.getLogger("sence_storetracker");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sence-storetracker-worker");
        t.setDaemon(true);
        return t;
    });

    public static void run(Runnable task) {
        // ExecutorService.submit()은 태스크 안에서 던진 예외를 Future에만 담아두고 아무 데도
        // 로그하지 않는다 - 아무도 future.get()을 안 부르면(우리는 안 부름) 예외가 완전히 조용히
        // 사라진다. 그러면 입출고 전송이 실패해도 원인을 추적할 방법이 없어지므로 직접 잡아서 남긴다.
        EXECUTOR.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("백그라운드 작업 중 예외 발생", t);
            }
        });
    }

    private Async() {
    }
}
