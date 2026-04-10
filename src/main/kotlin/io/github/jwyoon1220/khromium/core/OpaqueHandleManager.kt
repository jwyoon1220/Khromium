package io.github.jwyoon1220.khromium.core

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpaqueHandleManager implements the §8.1 "Opaque Handle" model from the Khromium spec.
 *
 * Design:
 *   - Every OS-level resource (native window handle, socket, media stream, etc.) is
 *     registered here and assigned a small integer *virtual handle* (hWnd).
 *   - JS code and the DOM layer only ever see the integer handle.
 *   - The true native pointer / resource reference is hidden inside this manager and
 *     never exposed outside of kernel code.
 *   - Before any handle-to-resource translation the manager validates:
 *       1. The handle number is known (not forged / out-of-range).
 *       2. The requesting tabId matches the owner tabId (cross-tab theft prevention).
 *   - Violation raises [SecurityBreachException].
 *
 * Thread-safety: all public methods are @Synchronized.
 *
 * @param T the underlying resource type (e.g. a JFrame, a Socket, a vlcj MediaPlayer).
 */
class OpaqueHandleManager<T : Any> {

    private val nextHandle  = AtomicInteger(1)
    private val handleTable = Int2ObjectOpenHashMap<HandleEntry<T>>()

    private data class HandleEntry<T>(
        val resource: T,
        val ownerTabId: String,
        val description: String
    )

    /**
     * Registers [resource] owned by [ownerTabId] and returns an opaque integer handle.
     * The handle is the *only* value ever passed to JS/DOM layers.
     */
    @Synchronized
    fun register(resource: T, ownerTabId: String, description: String = resource.javaClass.simpleName): Int {
        val handle = nextHandle.getAndIncrement()
        handleTable.put(handle, HandleEntry(resource, ownerTabId, description))
        return handle
    }

    /**
     * Resolves [handle] to the underlying resource.
     *
     * @param handle    The opaque integer handle.
     * @param callerTabId The tab requesting the translation.
     * @throws SecurityBreachException if the handle is unknown or ownership is wrong.
     */
    @Synchronized
    fun resolve(handle: Int, callerTabId: String): T {
        val entry = handleTable[handle]
            ?: throw SecurityBreachException(
                "Unknown handle $handle requested by tab '$callerTabId' — " +
                "possible handle-forging attack. Tab terminated."
            )

        if (entry.ownerTabId != callerTabId) {
            throw SecurityBreachException(
                "Cross-tab handle access: handle $handle owned by tab '${entry.ownerTabId}' " +
                "but accessed by tab '$callerTabId' — potential tab-escape attempt. Tab terminated."
            )
        }

        return entry.resource
    }

    /**
     * Unregisters [handle] after validating ownership.
     * Safe to call with an already-unregistered handle (no-op).
     */
    @Synchronized
    fun release(handle: Int, callerTabId: String) {
        val entry = handleTable[handle] ?: return

        if (entry.ownerTabId != callerTabId) {
            throw SecurityBreachException(
                "Cross-tab handle release: handle $handle owned by '${entry.ownerTabId}', " +
                "released by '${callerTabId}'."
            )
        }
        handleTable.remove(handle)
    }

    /**
     * Releases all handles owned by [tabId].
     * Called during tab shutdown to prevent handle leaks.
     */
    @Synchronized
    fun releaseTab(tabId: String) {
        val toRemove = handleTable.int2ObjectEntrySet()
            .filter { it.value.ownerTabId == tabId }
            .map { it.intKey }
        for (h in toRemove) handleTable.remove(h)
    }

    /** Returns a debug summary of all live handles (no resource values exposed). */
    @Synchronized
    fun debugSummary(): List<String> = handleTable.int2ObjectEntrySet().map { e ->
        "handle=${e.intKey} owner=${e.value.ownerTabId} desc=${e.value.description}"
    }
}
