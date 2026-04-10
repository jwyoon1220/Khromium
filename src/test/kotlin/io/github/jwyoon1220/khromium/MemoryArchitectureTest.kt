package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager
import io.github.jwyoon1220.khromium.core.SegmentationFaultException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryArchitectureTest {

    @Test
    fun `test PMM and VMM mapping`() {
        val pmm = PhysicalMemoryManager(1) // 1MB physical RAM
        val vmm = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)

        val vAddr = 0x1000L // 4096 (Page 1)
        val size = 4096 * 2 // 2 Pages

        // 1. Mapping
        assertTrue(vmm.map(vAddr, size))

        // 2. Translation
        val pAddrStart = vmm.translate(vAddr)
        val pAddrEnd = vmm.translate(vAddr + 4095)
        
        // 4095 offset should still be in the same physical page's range
        assertEquals(pAddrStart + 4095, pAddrEnd)

        // 3. Write/Read Verification
        val data = "Hello Khromium v2.3".toByteArray()
        val pAddr = vmm.translate(vAddr)
        val pageIdx = (pAddr / PhysicalMemoryManager.PAGE_SIZE).toInt()
        val offset = (pAddr % PhysicalMemoryManager.PAGE_SIZE).toInt()

        pmm.write(pageIdx, offset, data)
        
        val readData = pmm.read(pageIdx, offset, data.size)
        assertEquals(String(data), String(readData))
    }

    @Test
    fun `test segmentation fault`() {
        val pmm = PhysicalMemoryManager(1)
        val vmm = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)

        // translation for unmapped address should throw
        assertThrows<SegmentationFaultException> {
            vmm.translate(0xFFFFL)
        }
    }

    @Test
    fun `test memory quota`() {
        val pmm = PhysicalMemoryManager(1)
        val vmm = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE, maxMemoryBytes = 8192)

        assertTrue(vmm.map(0x0L, 8192))
        
        assertThrows<RuntimeException> {
            vmm.map(0x2000L, 4096) // Should exceed 8KB quota
        }
    }
}
