package io.github.jwyoon1220.khromium.core

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * FaultRetryPolicy implements the 5-retry fault-tolerance model described in the Khromium spec.
 *
 * Design:
 *   - A transient address-translation failure (SegmentationFaultException) is treated as a
 *     "soft fault" — network jitter, concurrent-map lag, or a timing race — and is retried
 *     up to MAX_RETRIES times with a brief exponential back-off.
 *   - If the same virtual address fails MAX_RETRIES times in a row without a successful
 *     translation, the policy treats it as evidence of a heap-spray or use-after-unmap attack
 *     and throws [SecurityBreachException], causing the tab to be terminated.
 *
 * Thread-safety: each tab (KProcess) should own its own [FaultRetryPolicy] instance.
 */
class FaultRetryPolicy(
    val tabId: String,
    val maxRetries: Int = MAX_RETRIES,
    val baseDelayMs: Long = BASE_DELAY_MS
) {
    companion object {
        const val MAX_RETRIES = 5
        const val BASE_DELAY_MS = 1L
        private val log = LoggerFactory.getLogger(FaultRetryPolicy::class.java)
    }

    // Tracks consecutive failure count per virtual page (vAddr / PAGE_SIZE).
    private val failureCounters = ConcurrentHashMap<Long, AtomicInteger>()

    /**
     * Executes [block] (typically a VMM translate + read/write operation) with retry logic.
     *
     * On [SegmentationFaultException]:
     *   - Increments the per-address failure counter.
     *   - Sleeps for 2^attempt × [baseDelayMs] milliseconds.
     *   - After [maxRetries] failures for the same virtual page, throws [SecurityBreachException].
     *
     * On success the failure counter for the address is reset.
     *
     * @param vAddr The virtual address being accessed (used to scope the failure counter).
     * @param block The operation to execute.
     */
    fun <T> withRetry(vAddr: Long, block: () -> T): T {
        val pageKey = vAddr / PhysicalMemoryManager.PAGE_SIZE
        val counter = failureCounters.getOrPut(pageKey) { AtomicInteger(0) }

        var lastException: SegmentationFaultException? = null
        for (attempt in 0 until maxRetries) {
            try {
                val result = block()
                counter.set(0) // reset on success
                return result
            } catch (e: SegmentationFaultException) {
                lastException = e
                val total = counter.incrementAndGet()
                if (total >= maxRetries) {
                    failureCounters.remove(pageKey)
                    log.warn(
                        "SECURITY: Tab '{}' address 0x{} failed {} consecutive translations — " +
                        "potential heap-spray/use-after-free. Tab terminated.",
                        tabId, vAddr.toString(16), total
                    )
                    throw SecurityBreachException(
                        "Tab '$tabId': address 0x${vAddr.toString(16)} failed $total consecutive " +
                        "translations — potential heap-spray / use-after-free attack. " +
                        "Tab terminated.",
                        e
                    )
                }
                // Exponential back-off: 1 ms, 2 ms, 4 ms …
                Thread.sleep(baseDelayMs shl attempt)
            }
        }

        // Should be unreachable, but satisfy the compiler
        throw SecurityBreachException(
            "Tab '$tabId': exceeded retry budget for address 0x${vAddr.toString(16)}.",
            lastException
        )
    }

    /** Clears all tracked failure counters (call on tab cleanup). */
    fun reset() = failureCounters.clear()
}
