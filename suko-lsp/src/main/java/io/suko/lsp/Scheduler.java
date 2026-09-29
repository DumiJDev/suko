package io.suko.lsp;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Relógio do debounce, injectável para os testes não dependerem de {@code sleep}. */
interface Scheduler {

    interface Cancellable {
        void cancel();
    }

    Cancellable schedule(Runnable task, long delayMillis);

    /** Uma thread daemon: o server nunca fica preso por causa dela. */
    final class Threaded implements Scheduler {
        private final ScheduledExecutorService executor;

        Threaded() {
            ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "suko-lsp-debounce");
                thread.setDaemon(true);
                return thread;
            });
            pool.setRemoveOnCancelPolicy(true);
            this.executor = pool;
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    }
}
