package io.github.jwyoon1220.khromium.core

import java.nio.ByteBuffer

/**
 * PMM (Physical Memory Manager): 하드웨어 물리 RAM을 시뮬레이션합니다.
 * 4KB 페이지 프레임 단위로 메모리를 관리하며, 비트맵을 통해 O(1) 할당을 보장합니다.
 */
class PhysicalMemoryManager(val totalSizeMega: Int) {
    companion object {
        const val PAGE_SIZE = 4096 // 4KB
    }

    val totalSizeBytes = totalSizeMega * 1024L * 1024L
    val totalPages = (totalSizeBytes / PAGE_SIZE).toInt()
    
    // Direct ByteBuffer: JVM GC의 간섭을 받지 않는 Off-heap 메모리
    private val ram: ByteBuffer = ByteBuffer.allocateDirect(totalSizeBytes.toInt())
    
    // 비트맵 기반 페이지 관리 (1 bit = 1 page)
    // 64비트 long 1개당 64개 페이지 관리
    private val bitmap = LongArray((totalPages + 63) / 64)
    
    private var freePages = totalPages

    /**
     * 비트 연산을 활용한 O(1) 페이지 할당
     * @return 할당된 페이지의 인덱스, 실패 시 -1
     */
    fun allocatePage(): Int {
        if (freePages <= 0) return -1
        
        for (i in bitmap.indices) {
            val entry = bitmap[i]
            if (entry != -1L) { // 모든 비트가 1이 아닌 경우 (여유 공간 있음)
                // 0인 비트의 위치를 찾음 (Long.inv()로 비트 반전 후 trailing zero 계산)
                val freeBit = java.lang.Long.numberOfTrailingZeros(entry.inv())
                val pageIdx = i * 64 + freeBit
                if (pageIdx < totalPages) {
                    bitmap[i] = entry or (1L shl freeBit)
                    freePages--
                    return pageIdx
                }
            }
        }
        return -1
    }

    /**
     * 특정 페이지 해제
     */
    fun freePage(pageIdx: Int) {
        if (pageIdx < 0 || pageIdx >= totalPages) return
        
        val i = pageIdx / 64
        val bit = pageIdx % 64
        
        val mask = 1L shl bit
        if ((bitmap[i] and mask) != 0L) {
            bitmap[i] = bitmap[i] and mask.inv()
            freePages++
        }
    }

    /**
     * 물리 페이지에 데이터 쓰기
     */
    fun write(pageIdx: Int, offset: Int, data: ByteArray) {
        val physicalAddr = pageIdx.toLong() * PAGE_SIZE + offset
        ram.position(physicalAddr.toInt())
        ram.put(data)
    }

    /**
     * 물리 페이지에서 데이터 읽기
     */
    fun read(pageIdx: Int, offset: Int, size: Int): ByteArray {
        val physicalAddr = pageIdx.toLong() * PAGE_SIZE + offset
        ram.position(physicalAddr.toInt())
        val result = ByteArray(size)
        ram.get(result)
        return result
    }
    
    val rawBuffer: ByteBuffer
        get() = ram.duplicate()
    
    val freePageCount
        get() = freePages

    /**
     * 시각화를 위한 블록 정보 반환 (단순화된 버전)
     */
    val debugBlocks: List<MemoryBlockInfo>
        get() {
            val blocks = mutableListOf<MemoryBlockInfo>()
            for (i in 0 until totalPages) {
                val bit = (bitmap[i / 64] shr (i % 64)) and 1L
                blocks.add(MemoryBlockInfo(i.toLong() * PAGE_SIZE, PAGE_SIZE, bit == 0L))
            }
            return blocks
        }
}

data class MemoryBlockInfo(val offset: Long, val size: Int, val isFree: Boolean)
