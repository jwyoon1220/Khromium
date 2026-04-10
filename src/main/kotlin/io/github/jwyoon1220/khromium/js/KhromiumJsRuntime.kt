package io.github.jwyoon1220.khromium.js

import io.github.jwyoon1220.khromium.core.KProcess
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-tab JavaScript runtime wrapper.
 *
 * Each browser tab creates its own [KhromiumJsRuntime] which:
 *   1. Acquires a reference to the shared [QuickJSEngine] (backed by a single C++ runtime).
 *   2. Checks [SharedBytecodeCache] for an MD5 hit before executing a script (AOT cache path).
 *   3. On cache miss, delegates to the QuickJS engine and stores the result in the shared cache.
 *   4. On [close], releases the engine reference and destroys the tab's private VMM resources.
 *
 * Thread safety: [execute] is synchronized on the shared engine singleton to serialise calls
 * against the underlying C++ global context.
 */
class KhromiumJsRuntime(
    val tabId: String,
    private val process: KProcess,
    private val sharedCache: SharedBytecodeCache
) : AutoCloseable {

    companion object {
        // Shared engine – the C++ QuickJS runtime is a process-wide singleton.
        private val engine   = QuickJSEngine()
        private val refCount = AtomicInteger(0)

        @Synchronized
        private fun acquireEngine() {
            if (refCount.getAndIncrement() == 0) engine.initRuntime()
        }

        @Synchronized
        private fun releaseEngine() {
            if (refCount.decrementAndGet() == 0) engine.destroyRuntime()
        }
    }

    init {
        acquireEngine()
    }

    /**
     * Executes [script] with MD5-based AOT caching.
     *
     * Cache hit  → returns the previously computed result from [SharedBytecodeCache]
     *              (no QuickJS invocation).
     * Cache miss → evaluates via QuickJS, stores the result, and returns it.
     */
    fun execute(script: String): String {
        val hash   = md5Hex(script)
        val cached = sharedCache.getCached(hash)
        if (cached != null) return cached

        val result = synchronized(engine) { engine.eval(script) }
        sharedCache.putCache(hash, result)
        return result
    }

    /**
     * Releases the QuickJS engine reference and frees all VMM pages belonging
     * to this tab's private virtual address space.
     */
    override fun close() {
        releaseEngine()
        process.destroy()
    }

    // --- helpers ---

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
