package io.adhush.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The little HTTP the TV drivers need (ADR 0025): one request, a body, a few
 * headers, a short timeout. A set's own HTTPS (Vizio) has a self-signed
 * certificate, so [trustAll] exists for that one host on the LAN; nothing
 * that leaves the house uses it.
 */
object MiniHttp {
    class Response(val code: Int, val body: String, val headers: Map<String, List<String>>) {
        val ok: Boolean get() = code in 200..299
        fun header(name: String): String? = headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()
    }

    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 3000,
        contentType: String = "application/json",
        trustAll: Boolean = false,
    ): Response {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        if (trustAll && conn is HttpsURLConnection) {
            conn.sslSocketFactory = trustAllContext().socketFactory
            conn.setHostnameVerifier { _, _ -> true }
        }
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8") } ?: ""
        val hdrs = conn.headerFields.filterKeys { it != null }.mapKeys { it.key as String }
        conn.disconnect()
        return Response(code, text, hdrs)
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 3000, trustAll: Boolean = false) = request("GET", url, null, headers, timeoutMs, trustAll = trustAll)
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 3000, contentType: String = "application/json", trustAll: Boolean = false) = request("POST", url, body, headers, timeoutMs, contentType, trustAll)
    fun put(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 3000, trustAll: Boolean = false) = request("PUT", url, body, headers, timeoutMs, trustAll = trustAll)

    /** Accepts any certificate: for a television's self-signed one, on the LAN, never for the wider internet. */
    fun trustAllContext(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), java.security.SecureRandom()) }
    }

    /** The first match's first group, or null: the drivers read the little JSON and XML they need this way. */
    fun find(text: String, pattern: String): String? = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.getOrNull(1)
}
