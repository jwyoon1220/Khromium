package io.github.jwyoon1220.khromium.js

import io.github.jwyoon1220.khromium.core.VMMAllocator
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager
import io.github.jwyoon1220.khromium.dom.KDOM

/**
 * DomBridge exposes a minimal DOM API surface to the JavaScript engine.
 *
 * It is bound to the JS global scope as the `document` object (and helpers)
 * so scripts can call:
 *
 *     document.getElementById("id")        → KDOMElementProxy
 *     element.setAttribute("key", "value")
 *     element.getAttribute("key")          → String
 *     element.innerHTML = "text"           (replaces first text-child)
 *     element.textContent                  → String
 *
 * All DOM mutations go through the VMM allocator, so they are fully
 * off-heap and isolated per-tab.
 *
 * This class is designed to be passed directly to NashornEngine.bindGlobal("document", bridge).
 */
class DomBridge(
    private val allocator: VMMAllocator,
    private var domRoot: Long
) {
    val vmm: VirtualMemoryManager get() = allocator.vmm

    // ── document API ─────────────────────────────────────────────────────────

    /**
     * Returns an element proxy for the element with the given id attribute,
     * or null if not found.
     */
    fun getElementById(id: String): KDOMElementProxy? {
        val ptr = findById(domRoot, id) ?: return null
        return KDOMElementProxy(ptr, allocator)
    }

    /**
     * Returns an element proxy for the first element with the given tag name,
     * or null if not found.
     */
    fun getElementsByTagName(tagName: String): Array<KDOMElementProxy> {
        val results = mutableListOf<Long>()
        findByTagName(domRoot, tagName.lowercase(), results)
        return results.map { KDOMElementProxy(it, allocator) }.toTypedArray()
    }

    /**
     * Creates a new element node in the tab's VMM and returns a proxy.
     */
    fun createElement(tagName: String): KDOMElementProxy {
        val ptr = KDOM.createElement(allocator, tagName)
        return KDOMElementProxy(ptr, allocator)
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun findById(nodePtr: Long, id: String): Long? {
        if (nodePtr == 0L) return null
        if (vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val attrId = getAttributeValue(nodePtr, "id")
            if (attrId == id) return nodePtr

            var child = vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                val result = findById(child, id)
                if (result != null) return result
                child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return null
    }

    private fun findByTagName(nodePtr: Long, tagName: String, results: MutableList<Long>) {
        if (nodePtr == 0L) return
        if (vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L) {
                val name = vmm.readString(namePtr).lowercase()
                if (name == tagName) results.add(nodePtr)
            }
            var child = vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                findByTagName(child, tagName, results)
                child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
    }

    private fun getAttributeValue(elementPtr: Long, attrName: String): String? {
        var attr = vmm.readLong(elementPtr + KDOM.OFFSET_ELEMENT_ATTRS)
        while (attr != 0L) {
            val namePtr = vmm.readLong(attr + KDOM.OFFSET_ATTR_NAME)
            if (namePtr != 0L && vmm.readString(namePtr) == attrName) {
                val valPtr = vmm.readLong(attr + KDOM.OFFSET_ATTR_VALUE)
                return if (valPtr != 0L) vmm.readString(valPtr) else ""
            }
            attr = vmm.readLong(attr + KDOM.OFFSET_ATTR_NEXT)
        }
        return null
    }
}

/**
 * Proxy object for a single VMM-resident DOM element.
 * Exposed to JS scripts via the [DomBridge].
 */
class KDOMElementProxy(
    val ptr: Long,
    private val allocator: VMMAllocator
) {
    private val vmm: VirtualMemoryManager get() = allocator.vmm

    /** Tag name of this element (read-only). */
    val tagName: String
        get() {
            val namePtr = vmm.readLong(ptr + KDOM.OFFSET_NAME)
            return if (namePtr != 0L) vmm.readString(namePtr) else ""
        }

    /** Sets an attribute on this element. */
    fun setAttribute(name: String, value: String) {
        KDOM.setAttribute(allocator, ptr, name, value)
    }

    /** Gets an attribute value, or empty string if not found. */
    fun getAttribute(name: String): String {
        var attr = vmm.readLong(ptr + KDOM.OFFSET_ELEMENT_ATTRS)
        while (attr != 0L) {
            val namePtr = vmm.readLong(attr + KDOM.OFFSET_ATTR_NAME)
            if (namePtr != 0L && vmm.readString(namePtr) == name) {
                val valPtr = vmm.readLong(attr + KDOM.OFFSET_ATTR_VALUE)
                return if (valPtr != 0L) vmm.readString(valPtr) else ""
            }
            attr = vmm.readLong(attr + KDOM.OFFSET_ATTR_NEXT)
        }
        return ""
    }

    /**
     * Sets the innerHTML of this element by replacing its first text child.
     * If no text child exists one is created and appended.
     */
    var innerHTML: String
        get() = textContent
        set(value) {
            // Find or create first text child
            var child = vmm.readLong(ptr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                if (vmm.readInt(child + KDOM.OFFSET_TYPE) == KDOM.TYPE_TEXT) {
                    // Update existing text node
                    val oldTextPtr = vmm.readLong(child + KDOM.OFFSET_TEXT_DATA)
                    if (oldTextPtr != 0L) allocator.free(oldTextPtr)
                    val newTextPtr = allocator.malloc(value.toByteArray(Charsets.UTF_8).size + 1)
                    vmm.writeString(newTextPtr, value)
                    vmm.writeLong(child + KDOM.OFFSET_TEXT_DATA, newTextPtr)
                    return
                }
                child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
            // No text child — create one
            val textNode = KDOM.createTextNode(allocator, value)
            KDOM.appendChild(vmm, ptr, textNode)
        }

    /** Returns concatenated text content of all text children. */
    val textContent: String
        get() {
            val sb = StringBuilder()
            var child = vmm.readLong(ptr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                if (vmm.readInt(child + KDOM.OFFSET_TYPE) == KDOM.TYPE_TEXT) {
                    val textPtr = vmm.readLong(child + KDOM.OFFSET_TEXT_DATA)
                    if (textPtr != 0L) sb.append(vmm.readString(textPtr))
                }
                child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
            return sb.toString()
        }

    /** Appends a child element proxy to this node. */
    fun appendChild(child: KDOMElementProxy) {
        KDOM.appendChild(vmm, ptr, child.ptr)
    }

    override fun toString(): String = "<${tagName}>"
}
