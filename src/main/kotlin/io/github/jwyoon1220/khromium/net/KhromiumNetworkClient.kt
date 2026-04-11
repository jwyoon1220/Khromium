package io.github.jwyoon1220.khromium.net

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * KhromiumNetworkClient is the browser's HTTP/HTTPS fetch layer.
 *
 * Design:
 *   - Uses the standard java.net API — no third-party HTTP library needed.
 *   - Follows up to [maxRedirects] 3xx redirects automatically.
 *   - Enforces TLS for HTTPS URLs (standard JDK JSSE verification).
 *   - Exposes a simple [fetch] entry-point suitable for both page navigation
 *     and sub-resource loading (scripts, stylesheets, images).
 *   - All I/O is blocking; callers should dispatch on a worker thread.
 *
 * Security:
 *   - HTTPS certificate verification is performed by the JDK's default TrustManager.
 *   - User-Agent is fixed to "Khromium/2.3" — no OS or JVM version leakage.
 *   - Response body is limited to [maxBodyBytes] to prevent OOM via hostile servers.
 */
class KhromiumNetworkClient(
    val userAgent: String = "Khromium/2.3",
    val maxRedirects: Int = 10,
    val maxBodyBytes: Int = 64 * 1024 * 1024 // 64 MB cap
) {

    private val logger = LoggerFactory.getLogger(KhromiumNetworkClient::class.java)

    /**
     * Fetches [request] and returns the [KhromiumResponse].
     *
     * @throws IOException on network errors or if the redirect limit is exceeded.
     */
    @Throws(IOException::class)
    fun fetch(request: KhromiumRequest): KhromiumResponse {
        var currentUrl = request.url
        var redirectsLeft = maxRedirects

        while (true) {
            logger.info("Connect {}", currentUrl)
            val connection = openConnection(currentUrl, request)
            try {
                val status    = connection.responseCode
                logger.info("{} responded {} OK", currentUrl, status)
                val headers   = connection.headerFields.filterKeys { it != null }
                val mimeType  = parseMimeType(connection.contentType ?: "text/html")
                val charset   = parseCharset(connection.contentType ?: "text/html")

                if (status in 300..399 && redirectsLeft > 0) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("3xx redirect with no Location header")
                    currentUrl = resolveUrl(currentUrl, location)
                    redirectsLeft--
                    continue
                }

                val stream  = if (status in 200..299) connection.inputStream
                              else connection.errorStream ?: connection.inputStream
                val body    = stream.use { it.readBytes(maxBodyBytes) }

                return KhromiumResponse(
                    url        = currentUrl,
                    statusCode = status,
                    headers    = headers,
                    body       = body,
                    mimeType   = mimeType,
                    charset    = charset
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Convenience overload: fetch a URL with GET defaults.
     */
    @Throws(IOException::class)
    fun get(url: String): KhromiumResponse = fetch(KhromiumRequest(url = url))

    // ── internals ────────────────────────────────────────────────────────────

    private fun openConnection(url: String, request: KhromiumRequest): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod   = request.method
        conn.connectTimeout  = request.connectTimeoutMs
        conn.readTimeout     = request.readTimeoutMs
        conn.instanceFollowRedirects = false  // handled manually above
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        conn.setRequestProperty("Accept-Charset", "UTF-8")

        for ((k, v) in request.headers) conn.setRequestProperty(k, v)

        if (request.body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(request.body) }
        }

        return conn
    }

    private fun parseMimeType(contentType: String): String =
        contentType.split(";").firstOrNull()?.trim() ?: "text/html"

    private fun parseCharset(contentType: String): String {
        val cs = contentType.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.removePrefix("charset=")
            ?.trim()
            ?.removeSurrounding("\"")
        return cs?.ifBlank { "UTF-8" } ?: "UTF-8"
    }

    private fun resolveUrl(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        val baseUri = URI(base)
        return baseUri.resolve(location).toString()
    }
}

/** Read at most [limit] bytes from this stream. */
private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
    val buf  = java.io.ByteArrayOutputStream()
    val tmp  = ByteArray(8192)
    var read: Int
    var total = 0
    while (this.read(tmp).also { read = it } != -1) {
        val toWrite = minOf(read, limit - total)
        buf.write(tmp, 0, toWrite)
        total += toWrite
        if (total >= limit) break
    }
    return buf.toByteArray()
}
