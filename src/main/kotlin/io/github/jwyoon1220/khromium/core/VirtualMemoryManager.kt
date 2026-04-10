package io.github.jwyoon1220.khromium.core

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

/**
 * VMM (Virtual Memory Manager): 가상 주소를 물리 주소로 변환하는 페이지 테이블을 관리합니다.
 * FastUtil의 Long2IntOpenHashMap을 사용하여 박싱 없이 O(1) 수준의 변환 속도를 제공합니다.
 */
class VirtualMemoryManager(
    val pmm: PhysicalMemoryManager,
    val type: VMMType,
    val maxMemoryBytes: Long = 512 * 1024 * 1024 // 탭별 512MB 쿼터
) {
    enum class VMMType { SHARED, PRIVATE }

    // 가상 페이지 번호(vPage) -> 물리 페이지 번호(pPage)
    private val pageTable = Long2IntOpenHashMap().apply {
        defaultReturnValue(-1)
    }

    private var allocatedBytes = 0L

    /**
     * map: 가상 주소 공간에 물리 페이지를 할당 및 매핑합니다.
     */
    fun map(vAddr: Long, size: Int): Boolean {
        if (allocatedBytes + size > maxMemoryBytes) {
            throw RuntimeException("MemoryLimitExceeded: 쿼터($maxMemoryBytes bytes)를 초과했습니다.")
        }

        val startPage = vAddr / PhysicalMemoryManager.PAGE_SIZE
        val numPages = (size + PhysicalMemoryManager.PAGE_SIZE - 1) / PhysicalMemoryManager.PAGE_SIZE

        for (i in 0 until numPages) {
            val pPage = pmm.allocatePage()
            if (pPage == -1) return false // 물리 메모리 부족
            pageTable.put(startPage + i, pPage)
        }

        allocatedBytes += size
        return true
    }

    /**
     * translate: 가상 주소를 물리 주소(Offset in DirectByteBuffer)로 변환합니다.
     */
    fun translate(vAddr: Long): Long {
        val vPage = vAddr / PhysicalMemoryManager.PAGE_SIZE
        val offset = vAddr % PhysicalMemoryManager.PAGE_SIZE
        
        val pPage = pageTable.get(vPage)
        if (pPage == -1) {
            throw SegmentationFaultException("Segmentation Fault: Invalid virtual address 0x${vAddr.toString(16)}")
        }
        
        return pPage.toLong() * PhysicalMemoryManager.PAGE_SIZE + offset
    }

    /**
     * unmap: 매핑 해제 및 물리 페이지 반환
     */
    fun unmap(vAddr: Long, size: Int) {
        val startPage = vAddr / PhysicalMemoryManager.PAGE_SIZE
        val numPages = (size + PhysicalMemoryManager.PAGE_SIZE - 1) / PhysicalMemoryManager.PAGE_SIZE

        for (i in 0 until numPages) {
            val pPage = pageTable.remove(startPage + i)
            if (pPage != -1) {
                pmm.freePage(pPage)
            }
        }
        allocatedBytes -= size
    }

    /**
     * 탭 종료 시 전체 메모리 강제 반환
     */
    fun destroy() {
        val pages = pageTable.values.toList()
        for (pPage in pages) {
            pmm.freePage(pPage)
        }
        pageTable.clear()
        allocatedBytes = 0
    }

    // --- Primitive I/O Methods (Cross-page handling) ---

    fun write(vAddr: Long, data: ByteArray) {
        var currentVAddr = vAddr
        var remaining = data.size
        var dataOffset = 0

        while (remaining > 0) {
            val pAddr = translate(currentVAddr)
            val offsetInPage = (pAddr % PhysicalMemoryManager.PAGE_SIZE).toInt()
            val spaceInPage = PhysicalMemoryManager.PAGE_SIZE - offsetInPage
            val toWrite = minOf(remaining, spaceInPage)

            val pageIdx = (pAddr / PhysicalMemoryManager.PAGE_SIZE).toInt()
            val chunk = data.copyOfRange(dataOffset, dataOffset + toWrite)
            pmm.write(pageIdx, offsetInPage, chunk)

            currentVAddr += toWrite
            dataOffset += toWrite
            remaining -= toWrite
        }
    }

    fun read(vAddr: Long, size: Int): ByteArray {
        val result = ByteArray(size)
        var currentVAddr = vAddr
        var remaining = size
        var dataOffset = 0

        while (remaining > 0) {
            val pAddr = translate(currentVAddr)
            val offsetInPage = (pAddr % PhysicalMemoryManager.PAGE_SIZE).toInt()
            val spaceInPage = PhysicalMemoryManager.PAGE_SIZE - offsetInPage
            val toRead = minOf(remaining, spaceInPage)

            val pageIdx = (pAddr / PhysicalMemoryManager.PAGE_SIZE).toInt()
            val chunk = pmm.read(pageIdx, offsetInPage, toRead)
            System.arraycopy(chunk, 0, result, dataOffset, toRead)

            currentVAddr += toRead
            dataOffset += toRead
            remaining -= toRead
        }
        return result
    }

    fun writeByte(vAddr: Long, value: Byte) {
        write(vAddr, byteArrayOf(value))
    }

    fun readByte(vAddr: Long): Byte {
        return read(vAddr, 1)[0]
    }

    fun writeInt(vAddr: Long, value: Int) {
        val buffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(value)
        write(vAddr, buffer.array())
    }

    fun readInt(vAddr: Long): Int {
        val data = read(vAddr, 4)
        return java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    }

    fun writeLong(vAddr: Long, value: Long) {
        val buffer = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(value)
        write(vAddr, buffer.array())
    }

    fun readLong(vAddr: Long): Long {
        val data = read(vAddr, 8)
        return java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
    }

    fun writeDouble(vAddr: Long, value: Double) {
        val buffer = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putDouble(value)
        write(vAddr, buffer.array())
    }

    fun readDouble(vAddr: Long): Double {
        val data = read(vAddr, 8)
        return java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
    }

    /**
     * Writes a null-terminated UTF-8 string to VMM.
     * Returns the number of bytes written (including null terminator).
     */
    fun writeString(vAddr: Long, str: String): Int {
        val bytes = str.toByteArray(Charsets.UTF_8)
        write(vAddr, bytes)
        writeByte(vAddr + bytes.size, 0) // Null terminator
        return bytes.size + 1
    }

    /**
     * Reads a null-terminated UTF-8 string from VMM.
     */
    fun readString(vAddr: Long): String {
        val bytes = mutableListOf<Byte>()
        var currentAddr = vAddr
        while (true) {
            val b = readByte(currentAddr)
            if (b.toInt() == 0) break
            bytes.add(b)
            currentAddr++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}

class SegmentationFaultException(message: String) : RuntimeException(message)
