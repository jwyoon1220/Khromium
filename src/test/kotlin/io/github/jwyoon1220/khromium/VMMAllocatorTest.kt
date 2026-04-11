package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.core.VMMAllocator
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [VMMAllocator] focusing on allocation logic, free, coalesce,
 * and OOM behaviour (canary/security aspects are covered in [CanarySecurityTest]).
 */
class VMMAllocatorTest {

    private fun makeAllocator(heapKb: Int = 512): Triple<PhysicalMemoryManager, VirtualMemoryManager, VMMAllocator> {
        val pmm   = PhysicalMemoryManager(4)                       // 4 MB physical RAM
        val vmm   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
        val alloc = VMMAllocator(vmm, 0x100000L, heapKb * 1024)
        return Triple(pmm, vmm, alloc)
    }

    // ── malloc basics ──────────────────────────────────────────────────────────

    @Test
    fun `malloc returns non-zero address`() {
        val (_, _, alloc) = makeAllocator()
        val ptr = alloc.malloc(16)
        assertTrue(ptr > 0L, "malloc must return a positive address")
    }

    @Test
    fun `malloc returns 8-byte-aligned address`() {
        val (_, _, alloc) = makeAllocator()
        // payload starts at heapStart + HEADER_SIZE (8), which is 8-byte aligned
        val ptr = alloc.malloc(16)
        assertEquals(0L, ptr % 8, "Payload address must be 8-byte aligned")
    }

    @Test
    fun `two sequential mallocs return non-overlapping addresses`() {
        val (_, _, alloc) = makeAllocator()
        val ptr1 = alloc.malloc(32)
        val ptr2 = alloc.malloc(32)
        assertNotEquals(ptr1, ptr2, "Two allocations must not return the same address")
        // Each block is at least HEADER(8) + alignedPayload(32) + CANARY(12) = 52 bytes apart
        assertTrue(Math.abs(ptr2 - ptr1) >= 32, "Allocations must not overlap")
    }

    @Test
    fun `malloc for different sizes all succeed`() {
        val (_, _, alloc) = makeAllocator()
        assertDoesNotThrow { alloc.malloc(1) }
        assertDoesNotThrow { alloc.malloc(8) }
        assertDoesNotThrow { alloc.malloc(64) }
        assertDoesNotThrow { alloc.malloc(256) }
        assertDoesNotThrow { alloc.malloc(1024) }
    }

    // ── free ──────────────────────────────────────────────────────────────────

    @Test
    fun `free allows re-allocation in the same region`() {
        val (_, _, alloc) = makeAllocator()
        val ptr = alloc.malloc(64)
        assertDoesNotThrow { alloc.free(ptr) }
        // After free, a new allocation should succeed (not OOM)
        assertDoesNotThrow { alloc.malloc(64) }
    }

    @Test
    fun `free invalid address is silently ignored`() {
        val (_, _, alloc) = makeAllocator()
        // Addresses outside the heap must not throw
        assertDoesNotThrow { alloc.free(0x0L) }
        assertDoesNotThrow { alloc.free(0x1L) }
    }

    @Test
    fun `double free throws IllegalStateException`() {
        val (_, _, alloc) = makeAllocator()
        val ptr = alloc.malloc(16)
        alloc.free(ptr)
        assertThrows<IllegalStateException> { alloc.free(ptr) }
    }

    // ── coalesce ──────────────────────────────────────────────────────────────

    @Test
    fun `freed blocks are coalesced so a large malloc succeeds after freeing all`() {
        val heapKb  = 128
        val (_, _, alloc) = makeAllocator(heapKb)
        // Allocate many small blocks filling roughly half the heap
        val ptrs = (1..50).map { alloc.malloc(128) }
        // Free them all — should trigger coalesce
        for (ptr in ptrs) alloc.free(ptr)
        // Now a single large allocation should succeed (heap is fully coalesced)
        assertDoesNotThrow { alloc.malloc(64 * 1024) }
    }

    @Test
    fun `three adjacent free blocks are merged into one`() {
        val (_, _, alloc) = makeAllocator()
        val p1 = alloc.malloc(16)
        val p2 = alloc.malloc(16)
        val p3 = alloc.malloc(16)
        alloc.free(p1)
        alloc.free(p2)
        alloc.free(p3)
        // After coalescing all three, a block larger than 16 bytes must be available
        assertDoesNotThrow { alloc.malloc(40) }
    }

    // ── OOM ───────────────────────────────────────────────────────────────────

    @Test
    fun `malloc throws OutOfMemoryError when heap is exhausted`() {
        // Tiny heap — 2 KB
        val pmm   = PhysicalMemoryManager(1)
        val vmm   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
        val alloc = VMMAllocator(vmm, 0x100000L, 2048)
        // Fill the heap
        runCatching { repeat(100) { alloc.malloc(64) } }
        // Next malloc must report OOM
        assertThrows<OutOfMemoryError> { alloc.malloc(1024) }
    }

    // ── data integrity ────────────────────────────────────────────────────────

    @Test
    fun `data written to allocated payload is read back correctly`() {
        val (_, vmm, alloc) = makeAllocator()
        val ptr  = alloc.malloc(16)
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        vmm.write(ptr, data)
        val read = vmm.read(ptr, 16)
        assertEquals(data.toList(), read.toList(), "Payload bytes must survive a round-trip")
    }

    @Test
    fun `multiple allocations preserve independent payloads`() {
        val (_, vmm, alloc) = makeAllocator()
        val ptr1 = alloc.malloc(8)
        val ptr2 = alloc.malloc(8)
        vmm.write(ptr1, byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(),
                                     0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte()))
        vmm.write(ptr2, byteArrayOf(0xBB.toByte(), 0xBB.toByte(), 0xBB.toByte(), 0xBB.toByte(),
                                     0xBB.toByte(), 0xBB.toByte(), 0xBB.toByte(), 0xBB.toByte()))
        val read1 = vmm.read(ptr1, 8)
        val read2 = vmm.read(ptr2, 8)
        assertTrue(read1.all { it == 0xAA.toByte() }, "ptr1 payload must be 0xAA")
        assertTrue(read2.all { it == 0xBB.toByte() }, "ptr2 payload must be 0xBB")
    }
}
