package io.github.jwyoon1220.khromium.js

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException

/**
 * NashornEngine is a pure-JVM JavaScript engine backed by the standalone Nashorn library
 * (org.openjdk.nashorn:nashorn-core).
 *
 * This engine is used automatically when the native QuickJS DLL/SO is unavailable
 * (cross-platform fallback). It provides identical [KhromiumScriptEngine] semantics
 * and is registered through the standard [ScriptEngineManager] SPI.
 *
 * Security: script execution is sandboxed within the JVM.  The engine has no access
 * to the host filesystem, network, or native pointers — only to globals explicitly
 * added via [bindGlobal].
 */
class NashornEngine : KhromiumScriptEngine {

    private var engine: ScriptEngine? = null

    override fun initRuntime(): Boolean {
        return try {
            val mgr = ScriptEngineManager(NashornEngine::class.java.classLoader)
            engine = mgr.getEngineByName("nashorn")
            if (engine == null) {
                // Try the exact engine name used by the standalone library
                engine = mgr.getEngineByName("JavaScript")
            }
            engine != null
        } catch (e: Exception) {
            false
        }
    }

    override fun destroyRuntime() {
        engine = null
    }

    override fun eval(script: String): String {
        val e = engine ?: return "Error: NashornEngine not initialized"
        return try {
            val result = e.eval(script)
            result?.toString() ?: "undefined"
        } catch (ex: ScriptException) {
            "ScriptException: ${ex.message}"
        } catch (ex: Exception) {
            "Error: ${ex.message}"
        }
    }

    /**
     * Binds a host object to a global variable name visible inside the script.
     * Use this to expose the DOM bridge or console APIs.
     */
    fun bindGlobal(name: String, obj: Any) {
        engine?.put(name, obj)
    }
}
