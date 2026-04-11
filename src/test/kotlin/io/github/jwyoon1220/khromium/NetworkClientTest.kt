package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.net.KhromiumNetworkClient
import io.github.jwyoon1220.khromium.net.KhromiumRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [KhromiumNetworkClient] covering request construction,
 * error handling, and URL normalisation helpers.
 *
 * Note: Tests that require real network I/O are excluded from CI
 * (they depend on external connectivity).  Logic tests run always.
 */
class NetworkClientTest {

    // ── constructor / defaults ────────────────────────────────────────────────

    @Test
    fun `default client has expected user agent`() {
        val client = KhromiumNetworkClient()
        assertEquals("Khromium/2.3", client.userAgent)
    }

    @Test
    fun `custom user agent is stored`() {
        val client = KhromiumNetworkClient(userAgent = "TestAgent/1.0")
        assertEquals("TestAgent/1.0", client.userAgent)
    }

    @Test
    fun `default maxRedirects is 10`() {
        val client = KhromiumNetworkClient()
        assertEquals(10, client.maxRedirects)
    }

    // ── KhromiumRequest defaults ──────────────────────────────────────────────

    @Test
    fun `KhromiumRequest defaults to GET`() {
        val req = KhromiumRequest("https://example.com")
        assertEquals("GET", req.method)
    }

    @Test
    fun `KhromiumRequest defaults to null body`() {
        val req = KhromiumRequest("https://example.com")
        assertEquals(null, req.body)
    }

    @Test
    fun `KhromiumRequest has 10s connect timeout by default`() {
        val req = KhromiumRequest("https://example.com")
        assertEquals(10_000, req.connectTimeoutMs)
    }

    @Test
    fun `KhromiumRequest has 30s read timeout by default`() {
        val req = KhromiumRequest("https://example.com")
        assertEquals(30_000, req.readTimeoutMs)
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    fun `fetch throws IOException for unreachable host`() {
        val client = KhromiumNetworkClient()
        val req    = KhromiumRequest(
            url              = "http://localhost:19999/nonexistent",
            connectTimeoutMs = 500,
            readTimeoutMs    = 500
        )
        assertThrows<java.io.IOException> { client.fetch(req) }
    }

    @Test
    fun `get is a convenience overload of fetch`() {
        // Verify it throws the same exception as fetch for an unreachable host
        val client = KhromiumNetworkClient()
        assertThrows<java.io.IOException> {
            client.get("http://localhost:19999/nonexistent")
        }
    }

    // ── KhromiumResponse structure ────────────────────────────────────────────

    @Test
    fun `KhromiumResponse bodyText decodes UTF-8 correctly`() {
        val body    = "안녕하세요".toByteArray(Charsets.UTF_8)
        val response = io.github.jwyoon1220.khromium.net.KhromiumResponse(
            url        = "https://example.com",
            statusCode = 200,
            headers    = emptyMap(),
            body       = body,
            mimeType   = "text/html",
            charset    = "UTF-8"
        )
        assertTrue(response.bodyText.contains("안녕하세요"))
    }

    @Test
    fun `KhromiumResponse bodyText uses specified charset`() {
        val text    = "Hello"
        val body    = text.toByteArray(Charsets.ISO_8859_1)
        val response = io.github.jwyoon1220.khromium.net.KhromiumResponse(
            url        = "https://example.com",
            statusCode = 200,
            headers    = emptyMap(),
            body       = body,
            mimeType   = "text/plain",
            charset    = "ISO-8859-1"
        )
        assertEquals("Hello", response.bodyText)
    }

    @Test
    fun `KhromiumResponse is4xx for 404`() {
        val response = io.github.jwyoon1220.khromium.net.KhromiumResponse(
            url        = "https://example.com",
            statusCode = 404,
            headers    = emptyMap(),
            body       = ByteArray(0),
            mimeType   = "text/html",
            charset    = "UTF-8"
        )
        assertTrue(response.isError, "404 must be isError")
        assertNotNull(response)
    }

    @Test
    fun `KhromiumResponse is2xx for 200`() {
        val response = io.github.jwyoon1220.khromium.net.KhromiumResponse(
            url        = "https://example.com",
            statusCode = 200,
            headers    = emptyMap(),
            body       = ByteArray(0),
            mimeType   = "text/html",
            charset    = "UTF-8"
        )
        assertTrue(response.isSuccess, "200 must be isSuccess")
    }
}
