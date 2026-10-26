package de.i4energie.calc

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Streckenausschnitt des Diagramms, per Button in der Action Strip umschaltbar. */
enum class Zoom(val windowKm: Double?) {
    /** Ganze Fahrt, SOC-Achse 0 bis 100 %. */
    ALL(null),
    KM100(100.0),
    KM30(30.0),
}

/**
 * Sichtbarer Bereich des Diagramms. X in km seit Fahrtbeginn, Y in % SOC.
 * Bei einem Zoom-Ausschnitt passt sich die SOC-Achse den sichtbaren Linien an.
 */
data class ChartWindow(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    /** Abstand der Gitterlinien in % SOC. */
    val yStep: Double,
) {
    companion object {
        /** Anteil des Fensters, der hinter dem Auto liegt. Der Rest zeigt die Prognose voraus. */
        const val BEHIND_SHARE = 0.25

        private val FULL_Y = Triple(0.0, 100.0, 20.0)

        fun of(f: Forecast, zoom: Zoom): ChartWindow {
            val full = f.xMax
            val w = zoom.windowKm
            if (w == null || !f.tripRunning || w >= full) {
                return ChartWindow(0.0, full, FULL_Y.first, FULL_Y.second, FULL_Y.third)
            }
            val lo = (f.lastKm - BEHIND_SHARE * w).coerceIn(0.0, full - w)
            return withYRange(f, lo, lo + w)
        }

        private fun withYRange(f: Forecast, xMin: Double, xMax: Double): ChartWindow {
            val ys = ArrayList<Double>()
            // Die BMW-Linie zählt nicht mit, sie kann weit weg liegen und den Ausschnitt sinnlos strecken.
            for (series in listOf(f.actual, f.plan, f.live)) collectVisible(series, xMin, xMax, ys)
            if (ys.isEmpty()) return ChartWindow(xMin, xMax, FULL_Y.first, FULL_Y.second, FULL_Y.third)

            var lo = ys.min()
            var hi = ys.max()
            val pad = max(2.0, (hi - lo) * 0.1)
            lo -= pad
            hi += pad
            val step = when {
                hi - lo <= 25 -> 5.0
                hi - lo <= 60 -> 10.0
                else -> 20.0
            }
            var yMin = (floor(lo / step) * step).coerceAtLeast(0.0)
            var yMax = (ceil(hi / step) * step).coerceAtMost(100.0)
            // Mindestens zwei Rasterzellen, sonst wirkt das Diagramm leer
            if (yMax - yMin < 2 * step) yMax = min(100.0, yMin + 2 * step)
            if (yMax - yMin < 2 * step) yMin = max(0.0, yMax - 2 * step)
            return ChartWindow(xMin, xMax, yMin, yMax, step)
        }

        /** Sammelt die Y-Werte einer Linie im Bereich, einschließlich der Schnittpunkte mit den Rändern. */
        private fun collectVisible(points: List<Point>, xMin: Double, xMax: Double, out: MutableList<Double>) {
            for (i in points.indices) {
                val p = points[i]
                if (p.x in xMin..xMax) out += p.y
                if (i == 0) continue
                val a = points[i - 1]
                if (a.x == p.x) continue
                for (edge in doubleArrayOf(xMin, xMax)) {
                    if (edge >= min(a.x, p.x) && edge <= max(a.x, p.x)) {
                        out += a.y + (edge - a.x) / (p.x - a.x) * (p.y - a.y)
                    }
                }
            }
        }
    }
}
