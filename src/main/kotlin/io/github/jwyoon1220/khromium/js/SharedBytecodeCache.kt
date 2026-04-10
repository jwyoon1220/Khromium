package io.github.jwyoon1220.khromium.js

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.core.VMMAllocator
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager

/**
 * Shared bytecode/result cache backed by Shared VMM.
 *
 * Stores script execution results keyed by MD5 hash, allowing the same script
 * to be reused across tabs without re-execution (AOT cache hit path).
 * Implements LRU-style eviction based on a configurable last-accessed threshold.
 *
 * Entry layout in VMM:
 *   [0..7]   last_accessed timestamp (Long, 8 bytes)
 *   [8..11]  data_size (Int, 4 bytes)
 *   [12..]   UTF-8 encoded result bytes
 */
class SharedBytecodeCache(
    pmm: PhysicalMemoryManager,
    cacheSize: Int = 4 * 1024 * 1024,
    val evictionThresholdMs: Long = 5L * 24 * 60 * 60 * 1000 // 5 days
) {
    companion object {
        private const val HEAP_START        = 0x200000L
        private const val TIMESTAMP_OFFSET  = 0L
        private const val DATA_SIZE_OFFSET  = 8L
        private const val DATA_OFFSET       = 12L
    }

    private val sharedVmm  = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.SHARED)
    private val allocator  = VMMAllocator(sharedVmm, HEAP_START, cacheSize)

    // MD5 hex string → virtual address of the cache entry
    private val index = Object2LongOpenHashMap<String>().apply { defaultReturnValue(-1L) }

    /**
     * Returns true when an unexpired entry for [hash] exists in the cache.
     */
    @Synchronized
    fun hasCached(hash: String): Boolean {
        val addr = index.getLong(hash)
        if (addr == -1L) return false
        if (isExpired(addr)) {
            evictEntry(hash, addr)
            return false
        }
        return true
    }

    /**
     * Returns the cached result string for [hash], or null on miss/expiry.
     * A cache hit refreshes the last-accessed timestamp.
     */
    @Synchronized
    fun getCached(hash: String): String? {
        val addr = index.getLong(hash)
        if (addr == -1L) return null
        if (isExpired(addr)) {
            evictEntry(hash, addr)
            return null
        }
        // Refresh timestamp (LRU touch)
        sharedVmm.writeLong(addr + TIMESTAMP_OFFSET, System.currentTimeMillis())
        val size  = sharedVmm.readInt(addr + DATA_SIZE_OFFSET)
        val bytes = sharedVmm.read(addr + DATA_OFFSET, size)
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Stores [data] in the Shared VMM under [hash].
     * No-ops if the hash is already cached. On OOM, runs expired-entry eviction
     * and silently skips caching (best-effort).
     */
    @Synchronized
    fun putCache(hash: String, data: String) {
        if (index.containsKey(hash)) return
        val bytes     = data.toByteArray(Charsets.UTF_8)
        val entrySize = (DATA_OFFSET + bytes.size).toInt()
        val addr = try {
            allocator.malloc(entrySize)
        } catch (_: OutOfMemoryError) {
            evictExpired()
            return
        }
        sharedVmm.writeLong(addr + TIMESTAMP_OFFSET, System.currentTimeMillis())
        sharedVmm.writeInt(addr + DATA_SIZE_OFFSET, bytes.size)
        sharedVmm.write(addr + DATA_OFFSET, bytes)
        index.put(hash, addr)
    }

    /**
     * Scans the index for expired entries and frees their VMM pages.
     * Called by the Eviction Daemon or on OOM pressure.
     */
    @Synchronized
    fun evictExpired() {
        val toEvict = ArrayList(index.keys).filter { hash ->
            val addr = index.getLong(hash)
            addr != -1L && isExpired(addr)
        }
        for (hash in toEvict) {
            val addr = index.getLong(hash)
            if (addr != -1L) evictEntry(hash, addr)
        }
    }

    // --- internals ---

    private fun isExpired(addr: Long): Boolean {
        val lastAccessed = sharedVmm.readLong(addr + TIMESTAMP_OFFSET)
        return System.currentTimeMillis() - lastAccessed > evictionThresholdMs
    }

    private fun evictEntry(hash: String, addr: Long) {
        index.removeLong(hash)
        allocator.free(addr)
    }
}
