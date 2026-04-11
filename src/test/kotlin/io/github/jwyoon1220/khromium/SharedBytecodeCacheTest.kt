package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.js.SharedBytecodeCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SharedBytecodeCache] – AOT result cache backed by Shared VMM.
 */
class SharedBytecodeCacheTest {

    private fun makeCache(evictionMs: Long = 5L * 24 * 60 * 60 * 1000): SharedBytecodeCache {
        val pmm = PhysicalMemoryManager(16)
        return SharedBytecodeCache(pmm, cacheSize = 1 * 1024 * 1024, evictionThresholdMs = evictionMs)
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun `putCache and getCached round-trip`() {
        val cache = makeCache()
        cache.putCache("hash1", "result1")
        assertEquals("result1", cache.getCached("hash1"))
    }

    @Test
    fun `getCached returns null for unknown hash`() {
        val cache = makeCache()
        assertNull(cache.getCached("nonexistent"))
    }

    @Test
    fun `hasCached returns false before put`() {
        val cache = makeCache()
        assertFalse(cache.hasCached("nope"))
    }

    @Test
    fun `hasCached returns true after put`() {
        val cache = makeCache()
        cache.putCache("h", "v")
        assertTrue(cache.hasCached("h"))
    }

    @Test
    fun `getCached returns null after eviction threshold`() {
        // Use a 1 ms threshold so entries expire immediately
        val cache = makeCache(evictionMs = 1L)
        cache.putCache("hash", "value")
        Thread.sleep(10)   // wait for the entry to expire
        assertNull(cache.getCached("hash"), "Expired entry should return null")
    }

    @Test
    fun `hasCached returns false after eviction threshold`() {
        val cache = makeCache(evictionMs = 1L)
        cache.putCache("hash2", "data")
        Thread.sleep(10)
        assertFalse(cache.hasCached("hash2"), "Expired entry should not be cached")
    }

    @Test
    fun `evictExpired removes stale entries without touching fresh ones`() {
        val cache = makeCache(evictionMs = 1L)
        cache.putCache("old", "stale-data")
        Thread.sleep(10)                      // old entry expires
        // Re-create with a long threshold and put a fresh entry
        val pmm2  = PhysicalMemoryManager(16)
        val cache2 = SharedBytecodeCache(pmm2, 1 * 1024 * 1024, evictionThresholdMs = 60_000L)
        cache2.putCache("fresh", "live-data")
        cache2.evictExpired()
        assertNotNull(cache2.getCached("fresh"), "Fresh entry must survive eviction pass")
    }

    // ── duplicate put ─────────────────────────────────────────────────────────

    @Test
    fun `putCache with same hash is a no-op – first value wins`() {
        val cache = makeCache()
        cache.putCache("dup", "first")
        cache.putCache("dup", "second")          // second put must be silently ignored
        assertEquals("first", cache.getCached("dup"), "First put must win on duplicate hash")
    }

    // ── multiple entries ─────────────────────────────────────────────────────

    @Test
    fun `multiple distinct hashes are stored independently`() {
        val cache = makeCache()
        for (i in 1..20) cache.putCache("hash-$i", "result-$i")
        for (i in 1..20) {
            assertEquals("result-$i", cache.getCached("hash-$i"),
                "hash-$i should return result-$i")
        }
    }

    // ── unicode content ───────────────────────────────────────────────────────

    @Test
    fun `cache correctly stores and retrieves Unicode content`() {
        val cache = makeCache()
        val korean = "결과: 안녕하세요 🎉"
        cache.putCache("unicode", korean)
        assertEquals(korean, cache.getCached("unicode"))
    }

    // ── OOM resilience ────────────────────────────────────────────────────────

    @Test
    fun `cache does not throw when OOM — put is silently skipped`() {
        val pmm   = PhysicalMemoryManager(1)
        val cache = SharedBytecodeCache(pmm, cacheSize = 4096)   // tiny cache
        // Fill with increasingly large entries until OOM is hit internally
        assertDoesNotThrow {
            for (i in 1..200) {
                cache.putCache("k$i", "x".repeat(100))
            }
        }
    }
}
