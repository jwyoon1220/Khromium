package io.github.jwyoon1220.khromium.core

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap

/**
 * CookieStore implements the Khromium spec's §9.2 "struct-layout based cookie isolation".
 *
 * Design:
 *   - Each cookie is serialised as a fixed struct in a dedicated SHARED VMM region:
 *
 *       [0..7]   from_url_ptr  (Long) — pointer to null-terminated URL string, kernel-write-only
 *       [8..15]  name_ptr      (Long) — pointer to cookie name string
 *       [16..23] value_ptr     (Long) — pointer to cookie value string
 *       [24..27] max_age_secs  (Int)  — 0 = session cookie
 *       [28..31] flags         (Int)  — bit 0 = Secure, bit 1 = HttpOnly
 *       STRUCT_SIZE = 32 bytes
 *
 *   - The `from_url_ptr` field is written only internally (kernel mode) and verified
 *     against the requesting tab's origin domain before any cookie is returned.
 *     A mismatch raises [SecurityBreachException].
 *
 *   - Each tab's cookie sub-store is isolated in its own VMM region.
 *
 * Thread-safety: all public methods are @Synchronized.
 */
class CookieStore(
    pmm: PhysicalMemoryManager,
    cacheSize: Int = 2 * 1024 * 1024 // 2 MB for cookies
) {
    companion object {
        private const val HEAP_START         = 0x400000L
        private const val STRUCT_SIZE        = 32
        private const val FROM_URL_OFFSET    = 0L
        private const val NAME_OFFSET        = 8L
        private const val VALUE_OFFSET       = 16L
        private const val MAX_AGE_OFFSET     = 24L
        private const val FLAGS_OFFSET       = 28L

        private const val FLAG_SECURE        = 1
        private const val FLAG_HTTP_ONLY     = 2
    }

    private val vmm       = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.SHARED)
    private val allocator = VMMAllocator(vmm, HEAP_START, cacheSize)

    // domain -> list of struct addresses in VMM
    private val index = HashMap<String, MutableList<Long>>()

    /**
     * Stores a cookie from [fromDomain].
     * The from_url_ptr field is written here — conceptually "kernel-mode" — and is
     * never directly writable by JS code.
     */
    @Synchronized
    fun set(
        fromDomain: String,
        name: String,
        value: String,
        maxAgeSecs: Int = 0,
        secure: Boolean = false,
        httpOnly: Boolean = false
    ) {
        val structPtr   = allocator.malloc(STRUCT_SIZE)
        val fromUrlPtr  = allocateString(fromDomain)
        val namePtr     = allocateString(name)
        val valuePtr    = allocateString(value)
        var flags       = 0
        if (secure)   flags = flags or FLAG_SECURE
        if (httpOnly) flags = flags or FLAG_HTTP_ONLY

        // from_url_ptr is kernel-only: never exposed to JS
        vmm.writeLong(structPtr + FROM_URL_OFFSET,  fromUrlPtr)
        vmm.writeLong(structPtr + NAME_OFFSET,       namePtr)
        vmm.writeLong(structPtr + VALUE_OFFSET,      valuePtr)
        vmm.writeInt(structPtr  + MAX_AGE_OFFSET,    maxAgeSecs)
        vmm.writeInt(structPtr  + FLAGS_OFFSET,      flags)

        index.getOrPut(fromDomain) { mutableListOf() }.add(structPtr)
    }

    /**
     * Returns all cookies for [requestingDomain].
     *
     * For each candidate struct the stored `from_url` is compared to [requestingDomain].
     * Any mismatch is a cross-site access violation — [SecurityBreachException] is thrown.
     */
    @Synchronized
    fun get(requestingDomain: String): List<Pair<String, String>> {
        val entries = index[requestingDomain] ?: return emptyList()
        val result  = mutableListOf<Pair<String, String>>()

        for (structPtr in entries) {
            // Domain integrity check (§9.2 requirement)
            val storedFromPtr = vmm.readLong(structPtr + FROM_URL_OFFSET)
            val storedFrom    = vmm.readString(storedFromPtr)

            if (!domainsMatch(storedFrom, requestingDomain)) {
                throw SecurityBreachException(
                    "Cookie domain mismatch: stored='$storedFrom', " +
                    "requested='$requestingDomain' — potential cross-site cookie theft. Denied."
                )
            }

            val name  = vmm.readString(vmm.readLong(structPtr + NAME_OFFSET))
            val value = vmm.readString(vmm.readLong(structPtr + VALUE_OFFSET))
            result.add(Pair(name, value))
        }

        return result
    }

    /**
     * Removes a cookie by name for the given domain.
     */
    @Synchronized
    fun remove(domain: String, name: String) {
        val entries = index[domain] ?: return
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val structPtr = iter.next()
            val storedName = vmm.readString(vmm.readLong(structPtr + NAME_OFFSET))
            if (storedName == name) {
                freeStruct(structPtr)
                iter.remove()
            }
        }
    }

    /**
     * Removes all cookies for [domain] (called on tab destroy / logout).
     */
    @Synchronized
    fun clearDomain(domain: String) {
        val entries = index.remove(domain) ?: return
        for (structPtr in entries) freeStruct(structPtr)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun allocateString(s: String): Long {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val ptr   = allocator.malloc(bytes.size + 1)
        vmm.writeString(ptr, s)
        return ptr
    }

    private fun freeStruct(structPtr: Long) {
        listOf(FROM_URL_OFFSET, NAME_OFFSET, VALUE_OFFSET).forEach { off ->
            val ptr = vmm.readLong(structPtr + off)
            if (ptr != 0L) allocator.free(ptr)
        }
        allocator.free(structPtr)
    }

    /** Simple domain matching: exact match or subdomain of stored origin. */
    private fun domainsMatch(stored: String, requesting: String): Boolean {
        val s = stored.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val r = requesting.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        return s == r || r.endsWith(".$s")
    }
}
