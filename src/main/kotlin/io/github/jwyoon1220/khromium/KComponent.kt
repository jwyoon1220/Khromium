package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.KProcess
import java.awt.Graphics
import javax.swing.JComponent

/**
 * A Swing JComponent that stores its core properties in isolated Virtual Memory (VMM)
 * instead of the JVM Heap.
 */
class KComponent(val process: KProcess) : JComponent() {
    // 32-byte struct layout
    // [0-7] name_ptr (Long)
    // [8-11] x (Int)
    // [12-15] y (Int)
    // [16-19] width (Int)
    // [20-23] height (Int)
    val hWnd: Long = process.allocator.malloc(32)
    val vmm = process.vmm

    init {
        // Init memory
        vmm.writeLong(hWnd, 0L)
        vmm.writeInt(hWnd + 8, 0)
        vmm.writeInt(hWnd + 12, 0)
        vmm.writeInt(hWnd + 16, 100)
        vmm.writeInt(hWnd + 20, 100)
    }

    var kName: String
        get() {
            val ptr = vmm.readLong(hWnd)
            if (ptr == 0L) return ""
            return vmm.readString(ptr)
        }
        set(value) {
            val oldPtr = vmm.readLong(hWnd)
            if (oldPtr != 0L) process.allocator.free(oldPtr)
            val newPtr = process.allocator.malloc(value.toByteArray(Charsets.UTF_8).size + 1)
            vmm.writeString(newPtr, value)
            vmm.writeLong(hWnd, newPtr)
            repaint()
        }

    var kX: Int
        get() = vmm.readInt(hWnd + 8)
        set(value) { vmm.writeInt(hWnd + 8, value); repaint() }

    var kY: Int
        get() = vmm.readInt(hWnd + 12)
        set(value) { vmm.writeInt(hWnd + 12, value); repaint() }

    var kWidth: Int
        get() = vmm.readInt(hWnd + 16)
        set(value) { vmm.writeInt(hWnd + 16, value); repaint() }

    var kHeight: Int
        get() = vmm.readInt(hWnd + 20)
        set(value) { vmm.writeInt(hWnd + 20, value); repaint() }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        // Read directly from virtual memory to render
        val rx = kX
        val ry = kY
        val rw = kWidth
        val rh = kHeight
        val rn = kName

        g.color = java.awt.Color.LIGHT_GRAY
        g.fillRect(rx, ry, rw, rh)
        g.color = java.awt.Color.BLACK
        g.drawRect(rx, ry, rw, rh)
        g.drawString(rn, rx + 10, ry + 20)
    }
}