package io.github.jwyoon1220.khromium.core

/**
 * A basic First-Fit memory allocator that operates on top of VirtualMemoryManager.
 * It manages a contiguous block of virtual memory (the "heap").
 * 
 * Block Header (8 bytes):
 * - Int: Size of the block (including header)
 * - Int: Status (1 = Free, 0 = Allocated)
 */
class VMMAllocator(
    val vmm: VirtualMemoryManager,
    val heapStart: Long,
    val heapSize: Int
) {
    companion object {
        const val HEADER_SIZE = 8
        const val STATUS_FREE = 1
        const val STATUS_ALLOC = 0
    }

    init {
        // Ensure the heap memory is mapped
        vmm.map(heapStart, heapSize)
        
        // Initialize the entire heap as one large free block
        writeHeader(heapStart, heapSize, STATUS_FREE)
    }

    private fun writeHeader(addr: Long, size: Int, status: Int) {
        vmm.writeInt(addr, size)
        vmm.writeInt(addr + 4, status)
    }

    private fun readSize(addr: Long): Int {
        return vmm.readInt(addr)
    }

    private fun readStatus(addr: Long): Int {
        return vmm.readInt(addr + 4)
    }

    /**
     * Allocates size bytes and returns the virtual address pointing to the payload.
     * Throws OutOfMemoryError if insufficient contiguous space.
     */
    fun malloc(payloadSize: Int): Long {
        // Ensure 8-byte alignment
        val alignedPayload = (payloadSize + 7) and 7.inv()
        val totalSizeNeeded = alignedPayload + HEADER_SIZE

        var current = heapStart
        val end = heapStart + heapSize

        while (current < end) {
            val blockSize = readSize(current)
            val status = readStatus(current)

            if (blockSize <= 0) {
                // Heap corruption protection
                throw IllegalStateException("Heap corruption at 0x${current.toString(16)}")
            }

            if (status == STATUS_FREE && blockSize >= totalSizeNeeded) {
                // Found a block. Can we split it?
                if (blockSize - totalSizeNeeded >= HEADER_SIZE + 8) { // Minimum split size (header + 8 payload)
                    // Split
                    val remainingSize = blockSize - totalSizeNeeded
                    val nextBlockAddr = current + totalSizeNeeded
                    
                    // Setup new free block
                    writeHeader(nextBlockAddr, remainingSize, STATUS_FREE)
                    // Setup allocated block
                    writeHeader(current, totalSizeNeeded, STATUS_ALLOC)
                } else {
                    // Don't split, just use the whole block
                    writeHeader(current, blockSize, STATUS_ALLOC)
                }
                
                return current + HEADER_SIZE
            }
            
            // Move to next block
            current += blockSize
        }
        
        throw OutOfMemoryError("VMMAllocator out of memory for $payloadSize bytes")
    }

    /**
     * Frees an allocated block pointing to the payload.
     */
    fun free(payloadAddr: Long) {
        if (payloadAddr < heapStart + HEADER_SIZE || payloadAddr >= heapStart + heapSize) {
            return // Invalid address
        }
        
        val headerAddr = payloadAddr - HEADER_SIZE
        val status = readStatus(headerAddr)
        
        if (status != STATUS_ALLOC) {
            throw IllegalStateException("Double free or corruption at 0x${payloadAddr.toString(16)}")
        }
        
        val size = readSize(headerAddr)
        writeHeader(headerAddr, size, STATUS_FREE)
        
        // Note: For a robust allocator, we would merge adjacent free blocks here.
        // For standard Step 2, a simple merge pass can be implemented later.
        coalesce()
    }
    
    private fun coalesce() {
        var current = heapStart
        val end = heapStart + heapSize
        
        while (current < end) {
            val size = readSize(current)
            val status = readStatus(current)
            
            if (status == STATUS_FREE) {
                var next = current + size
                while (next < end && readStatus(next) == STATUS_FREE) {
                    val nextSize = readSize(next)
                    writeHeader(current, size + nextSize, STATUS_FREE)
                    next += nextSize
                }
            }
            
            current += readSize(current)
        }
    }
}
