package io.github.jwyoon1220.khromium.dom

import io.github.jwyoon1220.khromium.core.VMMAllocator
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager

/**
 * KDOM represents the DOM tree inside the VirtualMemoryManager.
 * It does not use Java objects for Nodes, but static methods acting on pointers (Long).
 */
object KDOM {
    // Node Types
    const val TYPE_ELEMENT = 1
    const val TYPE_TEXT = 2

    // Offsets
    // Node Header (40 bytes)
    const val OFFSET_TYPE = 0       // Int
    const val OFFSET_NAME = 4       // Long (String pointer)
    const val OFFSET_PARENT = 12    // Long
    const val OFFSET_FIRST_CHILD = 20 // Long
    const val OFFSET_NEXT_SIB = 28  // Long
    const val OFFSET_PREV_SIB = 36  // Long
    const val NODE_HEADER_SIZE = 44

    // Element Extensions (Starts at 44)
    const val OFFSET_ELEMENT_ATTRS = 44 // Long
    const val ELEMENT_SIZE = NODE_HEADER_SIZE + 8

    // Text Extensions (Starts at 44)
    const val OFFSET_TEXT_DATA = 44 // Long
    const val TEXT_SIZE = NODE_HEADER_SIZE + 8

    // Attribute Struct (32 bytes)
    const val OFFSET_ATTR_NAME = 0  // Long
    const val OFFSET_ATTR_VALUE = 8 // Long
    const val OFFSET_ATTR_NEXT = 16 // Long
    const val ATTR_SIZE = 24

    fun createElement(allocator: VMMAllocator, name: String): Long {
        val ptr = allocator.malloc(ELEMENT_SIZE)
        val vmm = allocator.vmm
        
        vmm.writeInt(ptr + OFFSET_TYPE, TYPE_ELEMENT)
        
        val namePtr = allocator.malloc(name.toByteArray(Charsets.UTF_8).size + 1)
        vmm.writeString(namePtr, name)
        vmm.writeLong(ptr + OFFSET_NAME, namePtr)
        
        vmm.writeLong(ptr + OFFSET_PARENT, 0L)
        vmm.writeLong(ptr + OFFSET_FIRST_CHILD, 0L)
        vmm.writeLong(ptr + OFFSET_NEXT_SIB, 0L)
        vmm.writeLong(ptr + OFFSET_PREV_SIB, 0L)
        vmm.writeLong(ptr + OFFSET_ELEMENT_ATTRS, 0L)
        return ptr
    }

    fun createTextNode(allocator: VMMAllocator, text: String): Long {
        val ptr = allocator.malloc(TEXT_SIZE)
        val vmm = allocator.vmm
        
        vmm.writeInt(ptr + OFFSET_TYPE, TYPE_TEXT)
        vmm.writeLong(ptr + OFFSET_NAME, 0L) // #text
        vmm.writeLong(ptr + OFFSET_PARENT, 0L)
        vmm.writeLong(ptr + OFFSET_FIRST_CHILD, 0L)
        vmm.writeLong(ptr + OFFSET_NEXT_SIB, 0L)
        vmm.writeLong(ptr + OFFSET_PREV_SIB, 0L)
        
        val textPtr = allocator.malloc(text.toByteArray(Charsets.UTF_8).size + 1)
        vmm.writeString(textPtr, text)
        vmm.writeLong(ptr + OFFSET_TEXT_DATA, textPtr)
        return ptr
    }

    fun appendChild(vmm: VirtualMemoryManager, parent: Long, child: Long) {
        vmm.writeLong(child + OFFSET_PARENT, parent)
        
        val firstChild = vmm.readLong(parent + OFFSET_FIRST_CHILD)
        if (firstChild == 0L) {
            vmm.writeLong(parent + OFFSET_FIRST_CHILD, child)
        } else {
            // Find last sibling
            var current = firstChild
            while (true) {
                val next = vmm.readLong(current + OFFSET_NEXT_SIB)
                if (next == 0L) break
                current = next
            }
            vmm.writeLong(current + OFFSET_NEXT_SIB, child)
            vmm.writeLong(child + OFFSET_PREV_SIB, current)
        }
    }

    fun setAttribute(allocator: VMMAllocator, element: Long, name: String, value: String) {
        val vmm = allocator.vmm
        if (vmm.readInt(element + OFFSET_TYPE) != TYPE_ELEMENT) return
        
        // Create new attribute node
        val attrPtr = allocator.malloc(ATTR_SIZE)
        val namePtr = allocator.malloc(name.toByteArray(Charsets.UTF_8).size + 1)
        val valPtr = allocator.malloc(value.toByteArray(Charsets.UTF_8).size + 1)
        vmm.writeString(namePtr, name)
        vmm.writeString(valPtr, value)
        
        vmm.writeLong(attrPtr + OFFSET_ATTR_NAME, namePtr)
        vmm.writeLong(attrPtr + OFFSET_ATTR_VALUE, valPtr)
        vmm.writeLong(attrPtr + OFFSET_ATTR_NEXT, 0L)
        
        // Link it
        val head = vmm.readLong(element + OFFSET_ELEMENT_ATTRS)
        vmm.writeLong(attrPtr + OFFSET_ATTR_NEXT, head)
        vmm.writeLong(element + OFFSET_ELEMENT_ATTRS, attrPtr)
    }

    // Helper for debugging layout
    fun printTree(vmm: VirtualMemoryManager, root: Long, indent: Int = 0) {
        if (root == 0L) return
        val prefix = " ".repeat(indent * 2)
        val type = vmm.readInt(root + OFFSET_TYPE)
        
        if (type == TYPE_ELEMENT) {
            val namePtr = vmm.readLong(root + OFFSET_NAME)
            val name = vmm.readString(namePtr)
            println("$prefix<$name>")
            
            // Print attrs
            var attr = vmm.readLong(root + OFFSET_ELEMENT_ATTRS)
            while(attr != 0L) {
                val aName = vmm.readString(vmm.readLong(attr + OFFSET_ATTR_NAME))
                val aVal = vmm.readString(vmm.readLong(attr + OFFSET_ATTR_VALUE))
                println("$prefix  @$aName=\"$aVal\"")
                attr = vmm.readLong(attr + OFFSET_ATTR_NEXT)
            }
            
            var child = vmm.readLong(root + OFFSET_FIRST_CHILD)
            while(child != 0L) {
                printTree(vmm, child, indent + 1)
                child = vmm.readLong(child + OFFSET_NEXT_SIB)
            }
            println("$prefix</$name>")
        } else if (type == TYPE_TEXT) {
            val txtPtr = vmm.readLong(root + OFFSET_TEXT_DATA)
            val txt = vmm.readString(txtPtr).trim()
            if (txt.isNotEmpty()) {
                println("$prefix\"$txt\"")
            }
            // Texts dont have children in DOM, but check siblings
        }
    }
}
