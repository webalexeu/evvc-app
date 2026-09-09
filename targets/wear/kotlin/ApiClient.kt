package io.evcc.wear

import android.util.Base64
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface FetchOutcome {
    data class Success(val json: String) : FetchOutcome
    object NoData : FetchOutcome   // reachable, but the jq slice is null / empty
    object Failure : FetchOutcome  // network or auth error
}

/**
 * Minimal evcc API client: GET jq slices of /api/state and POST actions, with
 * optional basic auth. Ported verbatim from
 * targets/android-widget/kotlin/ApiClient.kt (widget #255); the only change is
 * `StoredServer` -> `ServerConfig` (standalone: no RN-written server file).
 * Runs on a background thread (call from a coroutine).
 */
object ApiClient {
    private const val TIMEOUT_MS = 15_000

    private fun base(server: ServerConfig): String = server.url.trimEnd('/')

    private fun authorize(conn: HttpURLConnection, server: ServerConfig) {
        if (server.authRequired && !server.username.isNullOrEmpty() && server.password != null) {
            val token = Base64.encodeToString(
                "${server.username}:${server.password}".toByteArray(),
                Base64.NO_WRAP,
            )
            conn.setRequestProperty("Authorization", "Basic $token")
        }
    }

    /** GET /api/state?jq=<jq>. Returns the raw response body on success. */
    fun fetch(server: ServerConfig, jq: String): FetchOutcome {
        val url = "${base(server)}/api/state?jq=" + URLEncoder.encode(jq, "UTF-8")
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                authorize(this, server)
            }
            try {
                if (conn.responseCode !in 200..299) return FetchOutcome.Failure
                val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (body.isEmpty() || body == "null" || body == "[]" || body == "{}") {
                    FetchOutcome.NoData
                } else {
                    FetchOutcome.Success(body)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(FetchOutcome.Failure)
    }

    /** Loadpoint titles for the config picker, index-aligned to .loadpoints[]. */
    fun loadpointTitles(server: ServerConfig): List<String> {
        val out = fetch(server, "[.loadpoints[].title]")
        if (out !is FetchOutcome.Success) return emptyList()
        return runCatching {
            val arr = JSONArray(out.json)
            (0 until arr.length()).map { i ->
                arr.optString(i).takeIf { it.isNotEmpty() && it != "null" } ?: "Loadpoint ${i + 1}"
            }
        }.getOrDefault(emptyList())
    }

    /** GET a single loadpoint slice by index. */
    fun loadpoint(server: ServerConfig, index: Int): FetchOutcome =
        fetch(server, ".loadpoints[$index]")

    /** POST to an API path, e.g. "/api/loadpoints/1/mode/pv". Returns success. */
    fun post(server: ServerConfig, path: String): Boolean {
        val p = if (path.startsWith("/")) path else "/$path"
        return runCatching {
            val conn = (URL(base(server) + p).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                authorize(this, server)
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    /** POST loadpoint mode. `index` is the 0-based UI index; the API path is 1-based. */
    fun setMode(server: ServerConfig, index: Int, mode: String): Boolean =
        post(server, "/api/loadpoints/${index + 1}/mode/$mode")
}
