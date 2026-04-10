package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.core.SecurityBreachException
import io.github.jwyoon1220.khromium.core.VMMAllocator
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the 12-byte ELF canary guard zone in [VMMAllocator].
 *
 * Canary layout (placed immediately after the user payload):
 *   [0..3]  MAGIC = 0x7F 'E' 'L' 'F'  (0x7F454C46)
 *   [4..11] GUARD = "KHROMIUM"         (0x4B48524F4D49554D)
 */
class CanarySecurityTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeAllocator(heapMb: Int = 1): Triple<PhysicalMemoryManager, VirtualMemoryManager, VMMAllocator> {
        val pmm = PhysicalMemoryManager(heapMb)
        val vmm = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
        val alloc = VMMAllocator(vmm, 0x100000L, (heapMb * 512 * 1024))
        return Triple(pmm, vmm, alloc)
    }

    // ── 1. Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `canary magic bytes are ELF header 0x7F E L F`() {
        val (_, vmm, alloc) = makeAllocator()
        val ptr = alloc.malloc(8)

        // Canary starts right after the 8-byte aligned payload
        val canaryAddr = ptr + 8
        val magic = vmm.readInt(canaryAddr)

        assertEquals(0x7F454C46, magic,
            "First 4 bytes of canary must be ELF magic 0x7F 'E' 'L' 'F'")
    }

    @Test
    fun `normal write within bounds does not corrupt canary`() {
        val (_, vmm, alloc) = makeAllocator()
        val ptr = alloc.malloc(16)

        // Write exactly 16 bytes — stays within the payload, canary untouched
        vmm.write(ptr, ByteArray(16) { 0x41 })

        // free() must complete without SecurityBreachException
        assertDoesNotThrow { alloc.free(ptr) }
    }

    // ── 2. Overflow detected on free() ────────────────────────────────────────

    @Test
    fun `1-byte overflow into canary is detected on free`() {
        val (_, vmm, alloc) = makeAllocator()
        val ptr = alloc.malloc(16)

        // Write 17 bytes: 16 payload + 1 byte into the canary zone
        vmm.write(ptr, ByteArray(17) { 0xCC.toByte() })

        val ex = assertThrows<SecurityBreachException> { alloc.free(ptr) }
        assertTrue(ex.message!!.contains("canary", ignoreCase = true),
            "Exception message should mention canary corruption")
    }

    @Test
    fun `full overwrite of canary is detected on free`() {
        val (_, vmm, alloc) = makeAllocator()
        val ptr = alloc.malloc(16)

        // Overwrite the entire canary zone (16 payload + 12 canary = 28 bytes)
        vmm.write(ptr, ByteArray(28) { 0xBB.toByte() })

        assertThrows<SecurityBreachException> { alloc.free(ptr) }
    }

    // ── 3. Overflow detected passively during a subsequent malloc() scan ──────

    @Test
    fun `overflow in live block is detected by the next malloc call`() {
        val (_, vmm, alloc) = makeAllocator()

        // Allocate two consecutive blocks; ptr1 is still live
        val ptr1 = alloc.malloc(16)
        alloc.malloc(16)   // ptr2 — just to fill the heap a bit

        // Corrupt ptr1's canary without freeing it
        vmm.write(ptr1, ByteArray(28) { 0xDE.toByte() })

        // A new malloc() scans past ptr1 and must detect the corruption
        assertThrows<SecurityBreachException> { alloc.malloc(8) }
    }

    // ── 4. Tab isolation — breach in tab-1 must not affect tab-2 ─────────────

    @Test
    fun `canary breach in one tab does not affect other tabs`() {
        // Shared physical RAM
        val pmm = PhysicalMemoryManager(4)

        // Tab 1 — will be attacked
        val vmm1   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
        val alloc1 = VMMAllocator(vmm1, 0x100000L, 512 * 1024)

        // Tab 2 — must survive completely
        val vmm2   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
        val alloc2 = VMMAllocator(vmm2, 0x300000L, 512 * 1024)

        val ptr1 = alloc1.malloc(16)
        val ptr2 = alloc2.malloc(16)

        // Simulate overflow attack in tab 1
        vmm1.write(ptr1, ByteArray(28) { 0xDE.toByte() })

        // Tab 1: SecurityBreachException — tab is killed
        assertThrows<SecurityBreachException> { alloc1.free(ptr1) }

        // Tab 2: completely unaffected — state is maintained
        assertDoesNotThrow { alloc2.free(ptr2) }
        assertDoesNotThrow { alloc2.malloc(8) }
    }
}
