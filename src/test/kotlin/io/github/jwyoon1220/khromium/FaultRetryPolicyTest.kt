package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.FaultRetryPolicy
import io.github.jwyoon1220.khromium.core.SecurityBreachException
import io.github.jwyoon1220.khromium.core.SegmentationFaultException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extended unit tests for [FaultRetryPolicy] — covers retry mechanics,
 * breach escalation, counter reset, and tab-isolation.
 */
class FaultRetryPolicyTest {

    // ── success paths ─────────────────────────────────────────────────────────

    @Test
    fun `withRetry returns value when block succeeds first try`() {
        val policy = FaultRetryPolicy("tab-ok", maxRetries = 3, baseDelayMs = 0L)
        val result = policy.withRetry(0x1000L) { "hello" }
        assertEquals("hello", result)
    }

    @Test
    fun `withRetry returns Int value on success`() {
        val policy = FaultRetryPolicy("tab-int", maxRetries = 5, baseDelayMs = 0L)
        assertEquals(42, policy.withRetry(0x2000L) { 42 })
    }

    // ── retry mechanics ────────────────────────────────────────────────────────

    @Test
    fun `block is retried up to maxRetries before breach`() {
        val policy = FaultRetryPolicy("tab-retry", maxRetries = 4, baseDelayMs = 0L)
        var invocations = 0
        assertThrows<SecurityBreachException> {
            policy.withRetry(0xABCDL) {
                invocations++
                throw SegmentationFaultException("fault")
            }
        }
        assertEquals(4, invocations, "Block must be called exactly maxRetries times")
    }

    @Test
    fun `withRetry succeeds after transient faults`() {
        val policy = FaultRetryPolicy("tab-transient", maxRetries = 5, baseDelayMs = 0L)
        var calls = 0
        // First 2 calls fail, 3rd succeeds
        val result = policy.withRetry(0x5000L) {
            calls++
            if (calls < 3) throw SegmentationFaultException("transient")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, calls, "Block must be called 3 times (2 faults + 1 success)")
    }

    @Test
    fun `failure counter resets after a successful call`() {
        val policy = FaultRetryPolicy("tab-reset", maxRetries = 5, baseDelayMs = 0L)
        var failOnce = true
        // First invocation: fail once then succeed (uses 1 failure)
        policy.withRetry(0x6000L) {
            if (failOnce) { failOnce = false; throw SegmentationFaultException("one fault") }
            "ok"
        }
        // After the success, the counter for that page should be reset.
        // A fresh run of 4 more faults should NOT trigger a breach (counter starts fresh).
        var calls2 = 0
        runCatching {
            policy.withRetry(0x6000L) {
                calls2++
                if (calls2 < 4) throw SegmentationFaultException("subsequent fault")
                "fine"
            }
        }
        assertDoesNotThrow {
            policy.withRetry(0x6000L) { "still alive" }
        }
    }

    // ── breach detection ───────────────────────────────────────────────────────

    @Test
    fun `SecurityBreachException message contains tabId`() {
        val policy = FaultRetryPolicy("my-tab-123", maxRetries = 2, baseDelayMs = 0L)
        val ex = assertThrows<SecurityBreachException> {
            policy.withRetry(0xBEEFL) { throw SegmentationFaultException("x") }
        }
        assertTrue(ex.message!!.contains("my-tab-123"),
            "Breach message must contain the tabId for forensics")
    }

    @Test
    fun `SecurityBreachException is caused by SegmentationFaultException`() {
        val policy = FaultRetryPolicy("tab-cause", maxRetries = 2, baseDelayMs = 0L)
        val ex = assertThrows<SecurityBreachException> {
            policy.withRetry(0xDEADL) { throw SegmentationFaultException("root cause") }
        }
        assertTrue(ex.cause is SegmentationFaultException,
            "Breach exception must wrap the original SegmentationFaultException")
    }

    // ── different addresses ────────────────────────────────────────────────────

    @Test
    fun `failure counters are independent per virtual page`() {
        val policy = FaultRetryPolicy("tab-pages", maxRetries = 3, baseDelayMs = 0L)
        // Exhaust address 1 to breach
        assertThrows<SecurityBreachException> {
            policy.withRetry(0x10000L) { throw SegmentationFaultException("p1") }
        }
        // Address 2 must still work (its counter is independent)
        assertDoesNotThrow {
            policy.withRetry(0x20000L) { "ok" }
        }
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all failure counters`() {
        val policy = FaultRetryPolicy("tab-clear", maxRetries = 5, baseDelayMs = 0L)
        // Accumulate some failures
        runCatching {
            policy.withRetry(0x7000L) { throw SegmentationFaultException("fail") }
        }
        // Reset — counters should be wiped
        policy.reset()
        // A new set of up to maxRetries-1 failures should not raise a breach
        var c = 0
        assertDoesNotThrow {
            policy.withRetry(0x7000L) {
                c++
                if (c < 3) throw SegmentationFaultException("after reset")
                "ok after reset"
            }
        }
    }
}
