package io.adhush.core

import java.security.MessageDigest

/** HTTP Digest authentication (RFC 2617, MD5, qop=auth) for Philips JointSpace 6 (ADR 0026). */
object Digest {
    fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

    /** The parameters of a WWW-Authenticate: Digest header. */
    fun challenge(header: String): Map<String, String> =
        Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").findAll(header.removePrefix("Digest").trim()).associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }

    fun response(user: String, password: String, method: String, uri: String, realm: String, nonce: String, qop: String?, nc: String, cnonce: String): String {
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")
        return if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
    }

    fun authorization(user: String, password: String, method: String, uri: String, header: String, cnonce: String = "0a4f113b", nc: String = "00000001"): String {
        val c = challenge(header)
        val realm = c["realm"] ?: ""; val nonce = c["nonce"] ?: ""
        val qop = c["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val resp = response(user, password, method, uri, realm, nonce, qop, nc, cnonce)
        val opaque = c["opaque"]?.let { ", opaque=\"$it\"" } ?: ""
        return if (qop != null) "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", qop=$qop, nc=$nc, cnonce=\"$cnonce\", response=\"$resp\"$opaque"
        else "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"$opaque"
    }
}
