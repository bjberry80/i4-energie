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
    /**
     * Nur ohne gültige Route: Strecke in km ab dem letzten Ist-Punkt, bis der SOC 0 % erreicht.
     * Die Linie dazu steckt in [live], eine Ankunft ([arrival]) gibt es dann nicht.
     */
    val projectedRangeKm: Double? = null,
    /** Restreichweite laut Fahrzeug, zum Vergleich mit [projectedRangeKm]. */
    val bmwRangeKm: Double? = null,
) {
    val tripRunning: Boolean get() = actual.isNotEmpty()
    val remainingKm: Double? get() = routeEndKm?.let { max(0.0, it - lastKm) }
}

/**
 * Prognoselogik, 1:1 aus der HA-Karte (plotly) übernommen. Ohne Android-Abhängigkeit.
 * Die Reichweitenprognose ohne Route ([projectRange]) ist eine Erweiterung der App.
 */
object ForecastCalc {

    /** Ab dieser gefahrenen Strecke wird der echte Verbrauch der Fahrt in die Prognose einbezogen. */
    const val MIN_LIVE_KM = 5.0

    /** Über diese Strecke geht die Prognose vom Durchschnittsverbrauch auf den Fahrtverbrauch über. */
    const val BLEND_KM = 30.0

    /** Obergrenze für die angezeigte Reichweite, damit die X-Achse nicht ausufert. */
    const val MAX_RANGE_KM = 1000.0

    /** Ergebnis von [projectRange]. [slope] = SOC-Verbrauch in % pro km. */
    data class Projection(val slope: Double, val rangeKm: Double, val consumptionDeltaPct: Int?)

    fun compute(trip: Trip?, route: Route?, bmwRangeKm: Double? = null): Forecast {
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

        // Ohne gültige Route: Reichweitenprognose bis 0 % statt Ankunft
        var projectedRangeKm: Double? = null
        if (!routeValid && actual.isNotEmpty()) {
            projectRange(actual, trip?.avgPctPerKm, bmwRangeKm)?.let { p ->
                projectedRangeKm = p.rangeKm
                consumptionDelta = p.consumptionDeltaPct
                live = listOf(Point(last, actual.last().y), Point(last + p.rangeKm, 0.0))
            }
        }

        val arrival = if (projectedRangeKm != null) null else live.lastOrNull()?.y ?: planLast?.y
        val xMax = maxOf(routeEnd ?: 0.0, last, 10.0, live.lastOrNull()?.x ?: 0.0)

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
            projectedRangeKm = projectedRangeKm,
            bmwRangeKm = bmwRangeKm?.takeIf { it > 0 },
        )
    }

    /**
     * Reichweitenprognose ohne Route. Der Verbrauch (% SOC pro km) kommt aus
     *  - dem Fahrtverbrauch (SOC-Abfall seit Fahrtbeginn), sobald mindestens [MIN_LIVE_KM] gefahren wurden,
     *  - dem Durchschnittsverbrauch [avgPctPerKm] des Fahrzeugs,
     *  - bei beiden gemischt: Der Fahrtverbrauch gewinnt über [BLEND_KM] km linear an Gewicht,
     *  - ohne beide aus der Restreichweite des Fahrzeugs ([bmwRangeKm], SOC / Reichweite).
     * `null`, wenn kein sinnvoller Verbrauch bestimmt werden kann oder der SOC schon 0 % ist.
     */
    fun projectRange(actual: List<Point>, avgPctPerKm: Double?, bmwRangeKm: Double?): Projection? {
        val socL = actual.lastOrNull()?.y ?: return null
        if (socL <= 0.0) return null

        val span = actual.last().x - actual.first().x
        val drop = actual.first().y - socL
        val live = if (span >= MIN_LIVE_KM && drop > 0) drop / span else null
        val avg = avgPctPerKm?.takeIf { it > 0 }
        val fromRange = bmwRangeKm?.takeIf { it > 0 }?.let { socL / it }

        val slope = when {
            live != null && avg != null -> {
                val w = (span / BLEND_KM).coerceIn(0.0, 1.0)
                w * live + (1 - w) * avg
            }
            live != null -> live
            avg != null -> avg
            else -> fromRange
        } ?: return null

        val delta = if (live != null && avg != null) ((live / avg - 1) * 100).roundToInt() else null
        return Projection(slope, (socL / slope).coerceAtMost(MAX_RANGE_KM), delta)
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
