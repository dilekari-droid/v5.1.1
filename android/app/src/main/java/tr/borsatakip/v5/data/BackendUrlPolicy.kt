package tr.borsatakip.v5.data

import java.net.URI

/** Canonical HTTPS backend-origin validation shared by settings, preflight and realtime clients. */
object BackendUrlPolicy {
    fun isValidHttps(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        val host = uri.host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            host.isNotBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.rawQuery == null &&
            host != "localhost" && !host.endsWith(".localhost") &&
            host !in setOf("127.0.0.1", "0.0.0.0", "::1", "10.0.2.2")
    }.getOrDefault(false)
}
