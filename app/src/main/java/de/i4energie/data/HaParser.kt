package de.i4energie.data

import de.i4energie.calc.Point
import de.i4energie.calc.ProfilePoint
import de.i4energie.calc.Route
import de.i4energie.calc.Trip
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wandelt Antworten von `GET /api/states/{entity_id}` defensiv in das Modell um.
 * Fehlende Attribute, `null`, `"unknown"`, `"unavailable"` und Zahlen als String werden toleriert.
 */
object HaParser {

    const val ENTITY_TRIP = "sensor.i4_fahrt"
    const val ENTITY_ROUTE = "sensor.i4_route"
    const val ENTITY_SOC = "sensor.i4_edrive40_battery_hv_state_of_charge"
    const val ENTITY_BMW_ARRIVAL = "sensor.i4_edrive40_trip_battery_charge_level_at_end_of_trip"
    const val ENTITY_RANGE = "sensor.i4_edrive40_range_ev_remaining_range"

    fun parseTrip(json: String?): Trip? {
        val obj = parseObject(json) ?: return null
        val attrs = obj.optJSONObject("attributes") ?: JSONObject()
        val points = pairs(attrs.opt("punkte")).mapNotNull { row ->
            val km = number(row.getOrNull(0))
            val soc = number(row.getOrNull(1))
            if (km != null && soc != null) Point(km, soc) else null
        }
        return Trip(
            startKm = number(attrs.opt("start_km")),
            startSoc = number(attrs.opt("start_soc")),
            points = points,
            consumption = number(attrs.opt("fahrtverbrauch")),
            avgPctPerKm = number(attrs.opt("verbrauch_pct_km")),
        )
    }

    fun parseRoute(json: String?): Route? {
        val obj = parseObject(json) ?: return null
        val attrs = obj.optJSONObject("attributes") ?: JSONObject()
        val profile = pairs(attrs.opt("profil")).map { row ->
            ProfilePoint(km = number(row.getOrNull(0)), elevation = number(row.getOrNull(1)))
        }
        val forecast = array(attrs.opt("prognose"))?.let { arr ->
            (0 until arr.length()).map { number(arr.opt(it)) }
        }.orEmpty()
        return Route(
            lengthKm = number(obj.opt("state")),
            destination = text(attrs.opt("ziel")),
            ascent = number(attrs.opt("aufstieg")),
            descent = number(attrs.opt("abstieg")),
            startKm = number(attrs.opt("start_km")),
            profile = profile,
            forecast = forecast,
        )
    }

    /** Liest den `state` einer Entität als Zahl. */
    fun parseNumericState(json: String?): Double? = parseObject(json)?.let { number(it.opt("state")) }

    // --- Hilfsfunktionen ---

    private fun parseObject(json: String?): JSONObject? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }

    /** Zahl aus Number oder String; `null` bei allem anderen oder nicht endlichen Werten. */
    internal fun number(value: Any?): Double? {
        val d = when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toDouble()
            is String -> value.trim().replace(',', '.').toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { it.isFinite() }
    }

    private fun text(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotEmpty() && it != "unknown" && it != "unavailable" }
        else -> value.toString()
    }

    /** JSONArray direkt oder als JSON-String verpackt. */
    private fun array(value: Any?): JSONArray? = when (value) {
        is JSONArray -> value
        is String -> try {
            JSONArray(value)
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    /** Liste von Zeilen `[a, b, …]`; kaputte Zeilen werden übersprungen. */
    private fun pairs(value: Any?): List<List<Any?>> {
        val arr = array(value) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val row = arr.optJSONArray(i) ?: return@mapNotNull null
            (0 until row.length()).map { row.opt(it) }
        }
    }
}
