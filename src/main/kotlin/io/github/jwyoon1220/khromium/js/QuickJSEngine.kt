package io.github.jwyoon1220.khromium.js

/**
 * Kotlin JNI Bridge to control the QuickJS JavaScript engine running in native C++.
 * This engine runs its allocations directly over the TLSF custom heap via Custom JSMallocFunctions.
 */
class QuickJSEngine {
    
    init {
        try {
           System.loadLibrary("KhromiumCore")
        } catch (e: UnsatisfiedLinkError) {
           println("WARNING: Could not load KhromiumCore DLL. Ensure it's compiled and in PATH.")
        }
    }

    /**
     * Initializes JS_NewRuntime2 with the TLSF JSMallocFunctions mapping
     * and JS_NewContext. Returns true on success.
     */
    external fun initRuntime(): Boolean

    /**
     * Cleans up JSContext and JSRuntime.
     */
    external fun destroyRuntime()

    /**
     * Evaluates a string of JavaScript and returns the resulting value as a String
     * or Exception string. All allocations by QuickJS during this call happen 
     * seamlessly within NativeMemoryManager's TLSF heap.
     */
    external fun eval(scriptSource: String): String
}
