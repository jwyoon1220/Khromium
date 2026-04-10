package io.github.jwyoon1220.khromium.core

/**
 * A First-Fit memory allocator that operates on top of VirtualMemoryManager.
 * It manages a contiguous block of virtual memory (the "heap").
 *
 * Every allocated block carries a 12-byte canary guard zone immediately after
 * the user payload.  If anything (e.g. a JS engine overflow) corrupts that zone,
 * the next [malloc] or [free] call raises [SecurityBreachException] so the
 * offending tab can be terminated while the rest of the kernel keeps running.
 *
 * Block layout (allocated):
 * ┌─────────────────────┬────────────────────┬──────────────────────────────┐
 * │  HEADER  (8 bytes)  │  PAYLOAD  (N bytes) │  CANARY  (12 bytes)          │
 * │  size | STATUS_ALLOC│  user data          │  0x7F454C46 | "KHROMIUM"     │
 * └─────────────────────┴────────────────────┴──────────────────────────────┘
 *   ^-- block start                                               ^-- canary
 *
 * The 4-byte magic 0x7F 'E' 'L' 'F' is an ELF-header easter egg.
 * The following 8 bytes spell "KHROMIUM" (0x4B48524F4D49554D).
 *
 * Block layout (free):
 * ┌─────────────────────┬───────────────────────────────────────────────────┐
 * │  HEADER  (8 bytes)  │  free space (no canary — avoids false positives)  │
 * │  size | STATUS_FREE │                                                   │
 * └─────────────────────┴───────────────────────────────────────────────────┘
 */
class VMMAllocator(
    val vmm: VirtualMemoryManager,
    val heapStart: Long,
    val heapSize: Int
) {
    companion object {
        const val HEADER_SIZE     = 8
        /** 12 bytes — chosen to fit ELF magic (4 B) + "KHROMIUM" (8 B) in the guard zone. */
        const val CANARY_SIZE     = 12
        /** Minimum usable payload size when splitting a free block (8-byte alignment floor). */
        private const val MIN_PAYLOAD_SIZE = 8
        const val STATUS_FREE     = 1
        const val STATUS_ALLOC    = 0

        // ── Canary magic ──────────────────────────────────────────────────
        // 4 bytes : ELF magic  → 0x7F 'E'(0x45) 'L'(0x4C) 'F'(0x46)
        // 8 bytes : "KHROMIUM" → 0x4B 0x48 0x52 0x4F 0x4D 0x49 0x55 0x4D
        // If either field is modified the tab is considered compromised.
        private const val CANARY_MAGIC: Int  = 0x7F454C46           // 0x7F 'E' 'L' 'F'
        private const val CANARY_GUARD: Long = 0x4B48524F4D49554DL  // "KHROMIUM"
    }

    init {
        // Map the heap region in the VMM and initialise it as one large free block
        vmm.map(heapStart, heapSize)
        writeHeader(heapStart, heapSize, STATUS_FREE)
    }

    /**
     * Allocates [payloadSize] bytes and returns the virtual address of the payload.
     *
     * On every first-fit scan we also validate the canary of each *allocated* block
     * we pass.  This gives early detection even before the victim block is freed.
     *
     * Throws [SecurityBreachException] if a canary is corrupted (overflow detected).
     * Throws [OutOfMemoryError] if there is no suitable free block.
     */
    fun malloc(payloadSize: Int): Long {
        // Ensure 8-byte alignment
        val alignedPayload    = (payloadSize + 7) and 7.inv()
        val totalSizeNeeded   = alignedPayload + HEADER_SIZE + CANARY_SIZE

        var current = heapStart
        val end     = heapStart + heapSize

        while (current < end) {
            val blockSize = readSize(current)
            val status    = readStatus(current)

            if (blockSize <= 0) {
                throw IllegalStateException("Heap corruption at 0x${current.toString(16)}")
            }

            // ── Passive canary scan: validate every allocated block we walk past ──
            if (status == STATUS_ALLOC) {
                validateCanary(current + blockSize - CANARY_SIZE)
            }

            if (status == STATUS_FREE && blockSize >= totalSizeNeeded) {
                // Minimum remainder that is worth splitting off as a free block:
                // must fit a header + MIN_PAYLOAD_SIZE bytes + canary when later allocated.
                val minSplit = HEADER_SIZE + CANARY_SIZE + MIN_PAYLOAD_SIZE
                if (blockSize - totalSizeNeeded >= minSplit) {
                    val remainingSize = blockSize - totalSizeNeeded
                    writeHeader(current + totalSizeNeeded, remainingSize, STATUS_FREE)
                    writeHeader(current, totalSizeNeeded, STATUS_ALLOC)
                } else {
                    writeHeader(current, blockSize, STATUS_ALLOC)
                }

                // Write canary at the very end of the allocated block
                writeCanary(current + readSize(current) - CANARY_SIZE)
                return current + HEADER_SIZE
            }

            current += blockSize
        }

        throw OutOfMemoryError("VMMAllocator out of memory for $payloadSize bytes")
    }

    /**
     * Frees the block whose payload starts at [payloadAddr].
     *
     * Validates the canary **before** marking the block free.  A corrupt canary
     * indicates an overflow attack: [SecurityBreachException] is thrown so the
     * caller's tab boundary can terminate the tab while the kernel survives.
     */
    fun free(payloadAddr: Long) {
        if (payloadAddr < heapStart + HEADER_SIZE || payloadAddr >= heapStart + heapSize) {
            return // invalid address — silently ignore
        }

        val headerAddr = payloadAddr - HEADER_SIZE
        val status     = readStatus(headerAddr)

        if (status != STATUS_ALLOC) {
            throw IllegalStateException("Double free or corruption at 0x${payloadAddr.toString(16)}")
        }

        val size = readSize(headerAddr)

        // ── Canary validation: throws SecurityBreachException if corrupted ──
        validateCanary(headerAddr + size - CANARY_SIZE)

        // Clear the canary so use-after-free cannot observe live magic bytes
        clearCanary(headerAddr + size - CANARY_SIZE)

        writeHeader(headerAddr, size, STATUS_FREE)
        coalesce()
    }

    // ── Canary helpers ────────────────────────────────────────────────────────

    private fun writeCanary(canaryAddr: Long) {
        vmm.writeInt(canaryAddr,      CANARY_MAGIC)
        vmm.writeLong(canaryAddr + 4, CANARY_GUARD)
    }

    /**
     * Reads the canary at [canaryAddr] and throws [SecurityBreachException] if
     * either the 4-byte ELF magic or the 8-byte "KHROMIUM" guard is wrong.
     * This is the sole detection point for overflow / heap-spray attacks.
     */
    private fun validateCanary(canaryAddr: Long) {
        val magic = vmm.readInt(canaryAddr)
        val guard = vmm.readLong(canaryAddr + 4)
        if (magic != CANARY_MAGIC || guard != CANARY_GUARD) {
            throw SecurityBreachException(
                "Heap canary corruption at 0x${canaryAddr.toString(16)}: " +
                "expected [magic=0x${CANARY_MAGIC.toUInt().toString(16)} | " +
                "guard=0x${CANARY_GUARD.toULong().toString(16)}], " +
                "got [magic=0x${magic.toUInt().toString(16)} | " +
                "guard=0x${guard.toULong().toString(16)}]. " +
                "Buffer-overflow / heap-spray attack detected — tab terminated."
            )
        }
    }

    private fun clearCanary(canaryAddr: Long) {
        vmm.writeInt(canaryAddr,      0)
        vmm.writeLong(canaryAddr + 4, 0L)
    }

    // ── Internal header I/O ───────────────────────────────────────────────────

    private fun writeHeader(addr: Long, size: Int, status: Int) {
        vmm.writeInt(addr,     size)
        vmm.writeInt(addr + 4, status)
    }

    private fun readSize(addr: Long): Int   = vmm.readInt(addr)
    private fun readStatus(addr: Long): Int = vmm.readInt(addr + 4)

    // ── Coalesce (merge adjacent free blocks) ────────────────────────────────

    /**
     * Single-pass coalesce: merges all contiguous free blocks into one.
     *
     * Previous implementation had a bug where the accumulated size was not tracked
     * correctly for runs of 3+ free blocks.  Fixed by accumulating [mergedSize]
     * separately and writing the merged header only once per run.
     */
    private fun coalesce() {
        var current = heapStart
        val end     = heapStart + heapSize

        while (current < end) {
            if (readStatus(current) == STATUS_FREE) {
                var mergedSize = readSize(current)
                var next       = current + mergedSize
                while (next < end && readStatus(next) == STATUS_FREE) {
                    mergedSize += readSize(next)
                    next        = current + mergedSize   // re-anchor from current
                }
                writeHeader(current, mergedSize, STATUS_FREE)
            }
            current += readSize(current)
        }
    }
}
