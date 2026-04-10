package io.github.jwyoon1220.khromium.js

/**
 * KhromiumScriptEngine is the common interface for all JavaScript engine backends.
 *
 * Current implementations:
 *   - [QuickJSEngine]   — native C++ QuickJS via JNI (Windows / Linux with native build)
 *   - [NashornEngine]   — JVM-native Nashorn fallback (any JDK 17+, no native library needed)
 *
 * Security contract: eval() must never expose raw pointers or native addresses to the caller.
 * All JS-to-host bridging must go through the [DomBridge] / [OpaqueHandleManager] APIs.
 */
interface KhromiumScriptEngine : AutoCloseable {

    /** Initialises the engine runtime. Returns true on success. */
    fun initRuntime(): Boolean

    /** Tears down the engine runtime and releases all resources. */
    fun destroyRuntime()

    /**
     * Evaluates [script] and returns the string representation of the result,
     * or an error description if evaluation fails.
     *
     * Implementations must be thread-safe (or the caller must synchronise externally).
     */
    fun eval(script: String): String

    override fun close() = destroyRuntime()
}
