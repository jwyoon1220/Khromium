package io.github.jwyoon1220.khromium.net

/**
 * A Khromium HTTP/HTTPS request.
 */
data class KhromiumRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 30_000
)
