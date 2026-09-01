package com.dynatrace.easytrade.contentcreator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dynatrace.easytrade.contentcreator.models.Pricing;

/**
 * UC10 profiling defect (thread leak -> native-memory growth).
 *
 * Each steady-state pricing cycle "hands off" every freshly generated candle to
 * a background writer thread that is supposed to flush asynchronously and then
 * exit. The bug: the writer never receives its flush signal, so every thread
 * parks forever instead of terminating. Because a fresh thread is created every
 * cycle and none are ever joined or reused (no pool), the live thread count
 * climbs monotonically and native memory (thread stacks) grows while the Java
 * heap stays flat — the thread/native analogue of the UC2 heap leak, and the
 * opposite of UC4 pool saturation (there the count is bounded).
 *
 * Always-on: no activation flag or env var (see the design doc §2.1, §6 Q4).
 * Intensity is tuned at the source via PRICING_WRITER_THREADS_PER_CYCLE — how
 * many writer threads leak per one-minute cycle (default: one per candle).
 */
public final class AsyncPricingWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncPricingWriter.class);
    private static final AtomicInteger LIVE_WRITERS = new AtomicInteger();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private AsyncPricingWriter() {
    }

    /**
     * Spawn a never-terminating writer thread for one candle. Named distinctly so
     * thread-state profiling attributes the ever-growing set of parked threads to
     * this leak site. This is the code creating the never-joined threads.
     */
    public static void submit(Pricing pricing) {
        Thread writer = new Thread(() -> awaitFlushSignal(pricing), "pricing-writer-" + SEQ.incrementAndGet());
        writer.setDaemon(true);
        writer.start();
        int live = LIVE_WRITERS.incrementAndGet();
        if (live % 100 == 0) {
            LOGGER.info("Async pricing writers alive: {}", live);
        }
    }

    /**
     * The writer parks waiting for a flush signal that is never delivered, so the
     * thread never returns and is never reclaimed. LockSupport.park can wake
     * spuriously, so re-park in a loop — the thread is leaked for the life of the
     * JVM.
     */
    private static void awaitFlushSignal(Pricing pricing) {
        while (true) {
            LockSupport.park(pricing);
        }
    }
}
