package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityComponentsTest {

    private fun makePmm() = PhysicalMemoryManager(8)

    // ── FaultRetryPolicy ──────────────────────────────────────────────────────

    @Test
    fun `FaultRetryPolicy succeeds on first attempt`() {
        val policy = FaultRetryPolicy("tab-1", maxRetries = 3, baseDelayMs = 0L)
        val result = policy.withRetry(0x1000L) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `FaultRetryPolicy raises SecurityBreachException after maxRetries failures`() {
        val policy = FaultRetryPolicy("tab-1", maxRetries = 3, baseDelayMs = 0L)
        var calls = 0
        val ex = assertThrows<SecurityBreachException> {
            policy.withRetry(0xDEAD_BEEFL) {
                calls++
                throw SegmentationFaultException("simulated fault")
            }
        }
        assertEquals(3, calls, "Expected exactly maxRetries invocations")
        assertTrue(ex.message!!.contains("tab-1"))
    }

    @Test
    fun `FaultRetryPolicy resets counter on success`() {
        val policy = FaultRetryPolicy("tab-1", maxRetries = 5, baseDelayMs = 0L)
        var fail = true
        // Fail twice then succeed — should NOT raise SecurityBreachException
        var calls = 0
        assertDoesNotThrow {
            repeat(3) {
                runCatching {
                    policy.withRetry(0x2000L) {
                        calls++
                        if (fail && calls < 3) throw SegmentationFaultException("transient")
                        fail = false
                        99
                    }
                }
            }
        }
    }

    // ── CookieStore ───────────────────────────────────────────────────────────

    @Test
    fun `CookieStore returns correct cookies for owner domain`() {
        val store = CookieStore(makePmm())
        store.set("example.com", "session", "abc123")
        store.set("example.com", "pref",    "dark")

        val cookies = store.get("example.com")
        assertEquals(2, cookies.size)
        assertTrue(cookies.any { it.first == "session" && it.second == "abc123" })
        assertTrue(cookies.any { it.first == "pref"    && it.second == "dark" })
    }

    @Test
    fun `CookieStore returns empty list for unregistered domain`() {
        val store = CookieStore(makePmm())
        store.set("example.com", "session", "xyz")
        val cookies = store.get("other.com")
        assertEquals(0, cookies.size)
    }

    @Test
    fun `CookieStore remove deletes specific cookie`() {
        val store = CookieStore(makePmm())
        store.set("example.com", "a", "1")
        store.set("example.com", "b", "2")
        store.remove("example.com", "a")
        val cookies = store.get("example.com")
        assertEquals(1, cookies.size)
        assertEquals("b", cookies[0].first)
    }

    @Test
    fun `CookieStore clearDomain removes all cookies for domain`() {
        val store = CookieStore(makePmm())
        store.set("example.com", "a", "1")
        store.set("example.com", "b", "2")
        store.clearDomain("example.com")
        assertEquals(0, store.get("example.com").size)
    }

    // ── OpaqueHandleManager ───────────────────────────────────────────────────

    @Test
    fun `OpaqueHandleManager resolves handle for correct tab`() {
        val mgr    = OpaqueHandleManager<String>()
        val handle = mgr.register("resource-A", "tab-1")
        val result = mgr.resolve(handle, "tab-1")
        assertEquals("resource-A", result)
    }

    @Test
    fun `OpaqueHandleManager rejects cross-tab resolve`() {
        val mgr    = OpaqueHandleManager<String>()
        val handle = mgr.register("secret", "tab-1")
        assertThrows<SecurityBreachException> {
            mgr.resolve(handle, "tab-2")
        }
    }

    @Test
    fun `OpaqueHandleManager rejects unknown handle`() {
        val mgr = OpaqueHandleManager<String>()
        assertThrows<SecurityBreachException> {
            mgr.resolve(9999, "tab-1")
        }
    }

    @Test
    fun `OpaqueHandleManager release cleans up handle`() {
        val mgr    = OpaqueHandleManager<String>()
        val handle = mgr.register("res", "tab-1")
        mgr.release(handle, "tab-1")
        assertThrows<SecurityBreachException> {
            mgr.resolve(handle, "tab-1")
        }
    }

    @Test
    fun `OpaqueHandleManager releaseTab cleans all handles for tab`() {
        val mgr = OpaqueHandleManager<String>()
        val h1  = mgr.register("r1", "tab-1")
        val h2  = mgr.register("r2", "tab-1")
        mgr.register("r3", "tab-2")
        mgr.releaseTab("tab-1")
        assertThrows<SecurityBreachException> { mgr.resolve(h1, "tab-1") }
        assertThrows<SecurityBreachException> { mgr.resolve(h2, "tab-1") }
        // tab-2's handle must survive
        assertDoesNotThrow { mgr.resolve(mgr.register("rx", "tab-2"), "tab-2") }
    }
}
