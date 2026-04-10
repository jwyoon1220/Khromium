package io.github.jwyoon1220.khromium.js

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * EvictionDaemon is a background single-threaded daemon that periodically calls
 * [SharedBytecodeCache.evictExpired] to reclaim VMM pages belonging to stale cache entries.
 *
 * Design (§6.2 LRU-based Eviction Daemon):
 *   - Runs on a daemon thread — it will not prevent JVM shutdown.
 *   - Interval is configurable; default is every 60 seconds.
 *   - The daemon is started lazily via [start] and stopped cleanly via [stop].
 *   - Thread-safety: the cache itself is @Synchronized; the daemon only calls evictExpired().
 */
class EvictionDaemon(
    private val cache: SharedBytecodeCache,
    private val intervalSec: Long = 60L
) {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "khromium-eviction-daemon").also { it.isDaemon = true }
    }

    @Volatile private var future: ScheduledFuture<*>? = null

    /** Starts the daemon. Idempotent — calling start() twice has no extra effect. */
    fun start() {
        if (future == null) {
            future = scheduler.scheduleAtFixedRate(
                ::runEviction,
                intervalSec,
                intervalSec,
                TimeUnit.SECONDS
            )
        }
    }

    /** Stops the daemon and awaits orderly shutdown (up to 5 seconds). */
    fun stop() {
        future?.cancel(false)
        future = null
        scheduler.shutdown()
        scheduler.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** Exposed for testing: run one eviction cycle immediately on the calling thread. */
    fun runEviction() {
        try {
            cache.evictExpired()
        } catch (_: Exception) {
            // Never let eviction failures escape to the daemon thread
        }
    }
}
