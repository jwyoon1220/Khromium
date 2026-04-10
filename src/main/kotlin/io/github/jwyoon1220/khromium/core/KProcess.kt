package io.github.jwyoon1220.khromium.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents an isolated browser tab or execution context.
 * Each KProcess has its own VirtualMemoryManager.
 */
class KProcess(
    val pmm: PhysicalMemoryManager,
    val isShared: Boolean = false
) {
    companion object {
        private val nextPid = AtomicInteger(1)
        const val HEAP_START = 0x100000L // Start heap at 1MB
        const val HEAP_SIZE = 10 * 1024 * 1024 // 10MB default heap per tab
    }
    
    val pid = nextPid.getAndIncrement()
    
    val vmm = VirtualMemoryManager(
        pmm = pmm,
        type = if (isShared) VirtualMemoryManager.VMMType.SHARED else VirtualMemoryManager.VMMType.PRIVATE
    )
    
    val allocator = VMMAllocator(vmm, HEAP_START, HEAP_SIZE)
    
    fun destroy() {
        vmm.destroy()
    }
}
