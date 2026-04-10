package io.github.jwyoon1220.khromium.net

/**
 * A Khromium HTTP/HTTPS response.
 */
data class KhromiumResponse(
    val url: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val mimeType: String,
    val charset: String = "UTF-8"
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)
    val isSuccess: Boolean get() = statusCode in 200..299
    val isRedirect: Boolean get() = statusCode in 300..399

    override fun toString() = "KhromiumResponse(url=$url, status=$statusCode, mime=$mimeType, size=${body.size})"
}
