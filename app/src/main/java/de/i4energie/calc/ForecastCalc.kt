package de.i4energie.calc

import kotlin.math.max
import kotlin.math.roundToInt

/** Ergebnis von [ForecastCalc.compute]. Alle x-Werte in km seit Fahrtbeginn. */
data class Forecast(
    /** `false`, wenn keine Route vorhanden ist oder sie zu einer früheren Fahrt gehört. */
    val routeValid: Boolean,
    val offset: Double,
    /** km des letzten Ist-Punkts (0 ohne Punkte). */
    val lastKm: Double,
    val actual: List<Point>,
    val plan: List<Point>,
    val live: List<Point>,
    /** x = km, y = Höhe in m. */
    val elevation: List<Point>,
    val arrival: Double?,
    val planEnd: Double?,
    val factor: Double,
    /** Verbrauchsabweichung in %, nur wenn `soll > 2`. */
    val consumptionDeltaPct: Int?,
    val routeEndKm: Double?,
    val xMax: Double,
) {
    val tripRunning: Boolean get() = actual.isNotEmpty()
    val remainingKm: Double? get() = routeEndKm?.let { max(0.0, it - lastKm) }
}

/**
 * Prognoselogik, 1:1 aus der HA-Karte (plotly) übernommen. Ohne Android-Abhängigkeit.
 */
object ForecastCalc {

    fun compute(trip: Trip?, route: Route?): Forecast {
        val actual = trip?.points.orEmpty().filter { it.x.isFinite() && it.y.isFinite() }
        val last = actual.lastOrNull()?.x ?: 0.0

        val routeStart = route?.startKm
        val tripStart = trip?.startKm
        val hasProfile = route != null && route.profile.any { it.km != null }
        val routeValid = hasProfile &&
            !(routeStart != null && tripStart != null && routeStart < tripStart - 1)

        var off = if (routeStart != null) routeStart - (tripStart ?: routeStart) else 0.0
        if (off > last + 1) off = 0.0 // Route wurde vor der Fahrt geplant -> ab 0

        val plan = ArrayList<Point>()
        val elevation = ArrayList<Point>()
        var routeEnd: Double? = null
        if (routeValid) {
            route!!.profile.forEachIndexed { i, p ->
                val km = p.km ?: return@forEachIndexed
                val x = km + off
                routeEnd = max(routeEnd ?: x, x)
                p.elevation?.let { elevation += Point(x, it) }
                route.forecast.getOrNull(i)?.let { plan += Point(x, it) }
            }
        }

        var live: List<Point> = emptyList()
        var factor = 1.0
        var consumptionDelta: Int? = null
        val planLast = plan.lastOrNull()
        if (actual.isNotEmpty() && plan.isNotEmpty() && last < planLast!!.x) {
            val xs = plan.map { it.x }
            val ys = plan.map { it.y }
            val pLast = interpolate(xs, ys, last)
            val s0 = actual.lastOrNull { it.x <= off }?.y ?: actual.first().y
            val socL = actual.last().y
            val soll = ys.first() - pLast
            factor = if (soll > 2) ((s0 - socL) / soll).coerceIn(0.5, 2.0) else 1.0
            if (soll > 2) consumptionDelta = ((factor - 1) * 100).roundToInt()
            live = buildList {
                add(Point(last, socL))
                plan.filter { it.x > last }.forEach { add(Point(it.x, socL - (pLast - it.y) * factor)) }
            }
        }

        val arrival = live.lastOrNull()?.y ?: planLast?.y
        val xMax = maxOf(routeEnd ?: 0.0, last, 10.0)

        return Forecast(
            routeValid = routeValid,
            offset = off,
            lastKm = last,
            actual = actual,
            plan = plan,
            live = live,
            elevation = elevation,
            arrival = arrival,
            planEnd = planLast?.y,
            factor = factor,
            consumptionDeltaPct = consumptionDelta,
            routeEndKm = routeEnd,
            xMax = xMax,
        )
    }

    /** Lineare Interpolation von [ys] über [xs] (aufsteigend), an den Enden geklemmt. */
    fun interpolate(xs: List<Double>, ys: List<Double>, x: Double): Double {
        require(xs.isNotEmpty() && xs.size == ys.size)
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val dx = xs[i] - xs[i - 1]
                if (dx <= 0) return ys[i]
                val t = (x - xs[i - 1]) / dx
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }
}
