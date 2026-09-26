package sence.playf.storetracker.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 네트워크/파일 IO를 렌더 스레드 밖에서 처리하기 위한 공용 백그라운드 실행기. */
public final class Async {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sence-storetracker-worker");
        t.setDaemon(true);
        return t;
    });

    public static void run(Runnable task) {
        EXECUTOR.submit(task);
    }

    private Async() {
    }
}
