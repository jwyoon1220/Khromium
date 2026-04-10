package io.github.jwyoon1220.khromium.js

import io.github.jwyoon1220.khromium.core.KProcess
import io.github.jwyoon1220.khromium.core.SecurityBreachException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-tab JavaScript runtime wrapper.
 *
 * Each browser tab creates its own [KhromiumJsRuntime] which:
 *   1. Selects the best available [KhromiumScriptEngine]:
 *        - [QuickJSEngine] when the native KhromiumCore library is loaded (preferred).
 *        - [NashornEngine] as a pure-JVM fallback (cross-platform).
 *   2. Checks [SharedBytecodeCache] for an MD5 hit before executing a script (AOT cache path).
 *   3. On cache miss, delegates to the engine and stores the result in the shared cache.
 *   4. On [close], releases the engine reference and destroys the tab's private VMM resources.
 *
 * Thread safety: [execute] is synchronized on the shared engine singleton to serialise calls
 * against the underlying engine's global context.
 *
 * Security: if a [SecurityBreachException] is raised (canary corruption, heap-spray, …)
 * the runtime calls [close] to destroy the tab's VMM before re-throwing.  The exception
 * propagates to the tab's UI boundary where only that tab is terminated; all other tabs
 * and the kernel continue running undisturbed.
 */
class KhromiumJsRuntime(
    val tabId: String,
    private val process: KProcess,
    private val sharedCache: SharedBytecodeCache,
    /** Optional: DOM root pointer for script-to-DOM interaction via DomBridge. */
    private val domRoot: Long = 0L
) : AutoCloseable {

    companion object {
        // Shared engine – the underlying runtime is a process-wide singleton.
        private val engine: KhromiumScriptEngine = selectEngine()
        private val refCount = AtomicInteger(0)

        /** Picks QuickJSEngine if the native library is available, else falls back to Nashorn. */
        private fun selectEngine(): KhromiumScriptEngine {
            return try {
                System.loadLibrary("KhromiumCore")
                QuickJSEngine()
            } catch (_: UnsatisfiedLinkError) {
                println("INFO: KhromiumCore native library not found — using NashornEngine fallback.")
                NashornEngine()
            }
        }

        @Synchronized
        private fun acquireEngine() {
            if (refCount.getAndIncrement() == 0) engine.initRuntime()
        }

        @Synchronized
        private fun releaseEngine() {
            if (refCount.decrementAndGet() == 0) engine.destroyRuntime()
        }
    }

    /** Guards against double-close (e.g. explicit close() followed by use{} cleanup). */
    private val closed = AtomicBoolean(false)

    init {
        acquireEngine()
    }

    /**
     * Executes [script] with MD5-based AOT caching.
     *
     * Cache hit  → returns the previously computed result from [SharedBytecodeCache]
     *              (no engine invocation).
     * Cache miss → evaluates via the selected engine, stores the result, and returns it.
     *
     * When [domRoot] is set and the engine is a [NashornEngine], the DOM bridge is bound
     * to the `document` global so scripts can call document.getElementById() etc.
     *
     * If a [SecurityBreachException] is detected during execution (canary corruption /
     * overflow attack), the tab's VMM is destroyed immediately and the exception is
     * re-thrown so the tab-boundary UI can display the security incident and stop
     * processing — without affecting any other tab.
     */
    fun execute(script: String): String {
        val hash   = md5Hex(script)
        val cached = sharedCache.getCached(hash)
        if (cached != null) return cached

        return try {
            val result = synchronized(engine) {
                // Bind DOM bridge when using the Nashorn engine and a DOM root is available
                if (engine is NashornEngine && domRoot != 0L) {
                    val bridge = DomBridge(process.allocator, domRoot)
                    engine.bindGlobal("document", bridge)
                    engine.bindGlobal("console", SimpleConsole())
                }
                engine.eval(script)
            }
            sharedCache.putCache(hash, result)
            result
        } catch (e: SecurityBreachException) {
            close()
            throw e
        }
    }

    /**
     * Releases the engine reference and frees all VMM pages belonging
     * to this tab's private virtual address space.
     *
     * Idempotent — safe to call multiple times (e.g. from both [execute] and the
     * Kotlin [use] block's implicit finally).
     */
    override fun close() {
        if (closed.getAndSet(true)) return
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

/** Minimal console object exposed to scripts (logs to stdout). */
class SimpleConsole {
    fun log(vararg args: Any?) = println(args.joinToString(" ") { it?.toString() ?: "null" })
    fun error(vararg args: Any?) = System.err.println(args.joinToString(" ") { it?.toString() ?: "null" })
    fun warn(vararg args: Any?) = println("[WARN] " + args.joinToString(" ") { it?.toString() ?: "null" })
}
