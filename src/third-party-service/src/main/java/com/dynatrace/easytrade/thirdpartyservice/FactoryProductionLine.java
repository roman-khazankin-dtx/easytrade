package com.dynatrace.easytrade.thirdpartyservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * UC3 profiling defect (lock contention / off-CPU wait).
 *
 * Models a single physical credit-card production line: only one card can be
 * "reserved" for manufacture at a time, so every incoming order must acquire a
 * single global monitor before it is accepted. The critical section holds the
 * monitor for a bounded, off-CPU interval (the machine's fixed cycle time), so
 * under concurrent load the request threads pile up in the BLOCKED state waiting
 * to enter the monitor while CPU stays idle — a textbook lock-contention signal
 * that is only visible in off-CPU / lock profiling, not in a CPU flamegraph.
 *
 * Always-on: profiling defects carry no activation flag or env var — scenarios
 * run on isolated, non-overlapping instances (see the design doc §2.1, §6 Q4).
 * The one intensity knob (FACTORY_SLOT_HOLD_MS) is tuned at the source, not
 * toggled at runtime.
 *
 * NOTE: {@code /v1/manufacturer} is normally driven by a single upstream
 * scheduler (credit-card-order-service WorkScheduler), so the contention only
 * manifests under a concurrent driver (see deploy/uc3-load).
 */
@Component
public class FactoryProductionLine {
    private static final Logger logger = LoggerFactory.getLogger(FactoryProductionLine.class);

    // How long the single production line stays reserved per card, in ms. Tune
    // the contention intensity here (longer hold => more threads blocked at once).
    private final long slotHoldMs = parseHoldMs();

    /**
     * Acquire the single global production slot, hold it for the fixed cycle time,
     * then release. Synchronized on this singleton bean, so all requests contend
     * on one monitor. Distinctly named so lock/monitor profiling attributes the
     * BLOCKED time to this frame unambiguously.
     */
    public synchronized void reserveSlot() {
        try {
            // Off-CPU hold: the production line is busy but the thread is not
            // burning CPU, so waiters show up as monitor-BLOCKED, not CPU work.
            Thread.sleep(slotHoldMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long parseHoldMs() {
        String raw = System.getenv("FACTORY_SLOT_HOLD_MS");
        long value = 150L;
        if (raw != null && !raw.isBlank()) {
            try {
                value = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid FACTORY_SLOT_HOLD_MS '{}', falling back to {} ms", raw, value);
            }
        }
        logger.info("FactoryProductionLine slot hold set to {} ms", value);
        return value;
    }
}
