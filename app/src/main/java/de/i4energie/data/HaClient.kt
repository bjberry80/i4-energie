package de.i4energie.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** HTTP-Fehler mit kurzer, deutscher Meldung. Enthält nie den Token. */
class HaException(message: String) : IOException(message)

/** Minimaler Client für die HA-REST-API. Blockierend, nur aus IO-Coroutines aufrufen. */
class HaClient(baseUrl: String, private val token: String) {

    private val base = normalizeBaseUrl(baseUrl)

    /** `GET /api/` muss `{"message": "API running."}` liefern. */
    fun testConnection() {
        val body = get("/api/") ?: throw HaException("Leere Antwort")
        if (!body.contains("API running.")) throw HaException("Unerwartete Antwort")
    }

    /** Liefert den JSON-Text der Entität oder `null`, wenn sie in HA nicht existiert (404). */
    fun state(entityId: String): String? =
        get("/api/states/" + URLEncoder.encode(entityId, "UTF-8"))

    private fun get(path: String): String? {
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw HaException("Ungültige URL")
        }
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            return when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                401, 403 -> throw HaException("Token ungültig (HTTP $code)")
                else -> throw HaException("HTTP $code")
            }
        } catch (e: HaException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw HaException("Zeitüberschreitung")
        } catch (e: java.net.UnknownHostException) {
            throw HaException("Server nicht gefunden")
        } catch (e: IOException) {
            throw HaException(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000

        fun normalizeBaseUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.endsWith("/api")) u = u.removeSuffix("/api")
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u
        }
    }
}
