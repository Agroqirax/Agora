package com.newoether.agora.util

/**
 * Normalizes a user-typed URL/host string for use in network calls.
 *
 * Users routinely omit the scheme (e.g. "api.githubcopilot.com/mcp"). `java.net.URI`
 * and `android.net.Uri` both parse such strings without throwing, producing a URI
 * with a null/empty scheme that only fails later — sometimes on a background thread
 * where the failure surfaces as an uncaught crash instead of a handled error. Run
 * every user-entered endpoint through this before it reaches an HTTP client.
 */
object UrlUtils {
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /**
     * Trims [input] and ensures it has an `http://`/`https://` scheme, defaulting to
     * `https://` when none is present. Returns null for blank input or a string that
     * already declares a non-http(s) scheme (e.g. "ftp://…"), since those can't be
     * turned into a valid request URL by prefixing.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (!SCHEME_PREFIX.containsMatchIn(trimmed)) return "https://$trimmed"
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else null
    }
}
