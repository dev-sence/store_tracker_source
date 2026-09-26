package sence.playf.storetracker.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** N틱 뒤에 클라이언트 스레드에서 실행되는 작업을 예약한다. */
public final class ClientDelay {
    private static final class Task {
        int ticksRemaining;
        final Runnable action;

        Task(int ticksRemaining, Runnable action) {
            this.ticksRemaining = ticksRemaining;
            this.action = action;
        }
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static boolean registered = false;

    public static void runAfterTicks(int ticks, Runnable action) {
        ensureRegistered();
        TASKS.add(new Task(ticks, action));
    }

    private static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Iterator<Task> it = TASKS.iterator();
            while (it.hasNext()) {
                Task task = it.next();
                if (--task.ticksRemaining <= 0) {
                    it.remove();
                    task.action.run();
                }
            }
        });
    }

    private ClientDelay() {
    }
}
