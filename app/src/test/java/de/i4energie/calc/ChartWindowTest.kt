package de.i4energie.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartWindowTest {

    /** Forecast mit nur der Ist-Linie, ohne Prognose: Die X-Achse endet am letzten Punkt (mindestens 10 km). */
    private fun actualOnly(vararg p: Pair<Double, Double>): Forecast {
        val f = ForecastCalc.compute(Trip(startKm = 1000.0, points = p.map { Point(it.first, it.second) }), route = null)
        return f.copy(live = emptyList(), projectedRangeKm = null, xMax = maxOf(f.lastKm, 10.0))
    }

    private val slowDrop = arrayOf(
        0.0 to 85.0, 10.0 to 84.0, 20.0 to 83.0, 30.0 to 82.0, 40.0 to 81.0, 50.0 to 80.0,
    )

    @Test
    fun all_showsWholeTripWithFullSocAxis() {
        val w = ChartWindow.of(actualOnly(*slowDrop), Zoom.ALL)

        assertEquals(0.0, w.xMin, 1e-9)
        assertEquals(50.0, w.xMax, 1e-9)
        assertEquals(0.0, w.yMin, 1e-9)
        assertEquals(100.0, w.yMax, 1e-9)
        assertEquals(20.0, w.yStep, 1e-9)
    }

    @Test
    fun zoom_windowEndsAtTripEndAndAdaptsSocAxis() {
        // 30-km-Fenster am Ende der Fahrt: sichtbar sind 20..50 km mit 83..80 %
        val w = ChartWindow.of(actualOnly(*slowDrop), Zoom.KM30)

        assertEquals(20.0, w.xMin, 1e-9)
        assertEquals(50.0, w.xMax, 1e-9)
        assertEquals(75.0, w.yMin, 1e-9)
        assertEquals(85.0, w.yMax, 1e-9)
        assertEquals(5.0, w.yStep, 1e-9)
    }

    @Test
    fun zoom_keepsCarAtAboutOneQuarterFromTheLeft() {
        val points = listOf(Point(0.0, 90.0), Point(100.0, 70.0), Point(200.0, 50.0), Point(300.0, 30.0))

        // Ohne Strecke voraus ist das Fenster ans Fahrtende geklemmt
        val atEnd = ChartWindow.of(actualOnly(*points.map { it.x to it.y }.toTypedArray()), Zoom.KM100)
        assertEquals(200.0, atEnd.xMin, 1e-9)
        assertEquals(300.0, atEnd.xMax, 1e-9)

        // Mit Route bis 500 km: Das Auto bei 300 km liegt 25 km vom linken Rand
        val route = Route(
            startKm = 1000.0,
            profile = listOf(ProfilePoint(0.0, 10.0), ProfilePoint(500.0, 10.0)),
            forecast = listOf(90.0, 10.0),
        )
        val withRoute = ChartWindow.of(ForecastCalc.compute(Trip(startKm = 1000.0, points = points), route), Zoom.KM100)
        assertEquals(275.0, withRoute.xMin, 1e-9)
        assertEquals(375.0, withRoute.xMax, 1e-9)
    }

    @Test
    fun zoom_clampsAtStartOfTrip() {
        val route = Route(
            startKm = 1000.0,
            profile = listOf(ProfilePoint(0.0, 10.0), ProfilePoint(300.0, 10.0)),
            forecast = listOf(90.0, 20.0),
        )
        val early = ForecastCalc.compute(
            Trip(startKm = 1000.0, points = listOf(Point(0.0, 90.0), Point(5.0, 89.0))),
            route,
        )
        val w = ChartWindow.of(early, Zoom.KM30)

        // Das Auto steht bei 5 km, ein Fenster mit 25 % Platz dahinter würde vor 0 beginnen
        assertEquals(0.0, w.xMin, 1e-9)
        assertEquals(30.0, w.xMax, 1e-9)
    }

    @Test
    fun zoom_biggerThanTripFallsBackToWholeTrip() {
        val w = ChartWindow.of(actualOnly(0.0 to 90.0, 20.0 to 85.0), Zoom.KM100)

        assertEquals(0.0, w.xMin, 1e-9)
        assertEquals(20.0, w.xMax, 1e-9)
        assertEquals(0.0, w.yMin, 1e-9)
        assertEquals(100.0, w.yMax, 1e-9)
    }

    @Test
    fun zoom_withoutTripPointsShowsWholeRange() {
        val route = Route(
            startKm = 1000.0,
            profile = listOf(ProfilePoint(0.0, 10.0), ProfilePoint(200.0, 10.0)),
            forecast = listOf(80.0, 30.0),
        )
        val f = ForecastCalc.compute(Trip(startKm = 1000.0), route)
        val w = ChartWindow.of(f, Zoom.KM30)

        assertEquals(0.0, w.xMin, 1e-9)
        assertEquals(200.0, w.xMax, 1e-9)
        assertEquals(100.0, w.yMax, 1e-9)
    }

    @Test
    fun zoom_socAxisStaysInsideZeroAndHundred() {
        val w = ChartWindow.of(actualOnly(0.0 to 100.0, 40.0 to 99.0, 80.0 to 99.5), Zoom.KM30)

        assertTrue(w.yMin >= 0.0)
        assertTrue(w.yMax <= 100.0)
        assertTrue(w.yMax - w.yMin >= 2 * w.yStep - 1e-9)
    }

    @Test
    fun zoom_steepDropUsesCoarserGrid() {
        // Im letzten 30-km-Fenster fallen 40 %: Spanne > 25 -> Raster 10
        val w = ChartWindow.of(actualOnly(0.0 to 100.0, 30.0 to 90.0, 60.0 to 50.0), Zoom.KM30)

        assertEquals(10.0, w.yStep, 1e-9)
        assertTrue(w.yMin <= 50.0 && w.yMax >= 90.0)
    }

    @Test
    fun zoom_includesPredictionLinesInSocAxis() {
        // Ohne Route: Die Reichweitenprognose fällt bis 0 %. Im 30-km-Fenster davor sind nur ~10 % davon zu sehen.
        val trip = Trip(
            startKm = 1000.0,
            points = listOf(Point(0.0, 90.0), Point(20.0, 80.0), Point(40.0, 70.0)),
            avgPctPerKm = 0.5,
        )
        val f = ForecastCalc.compute(trip, route = null)
        val w = ChartWindow.of(f, Zoom.KM30)

        assertTrue(w.xMax - w.xMin == 30.0)
        // Die Prognose steigt in diesem Fenster nicht über das Auto hinaus nach oben
        assertTrue(w.yMax <= 100.0 && w.yMin >= 0.0)
        assertTrue(w.yMin < 70.0)
    }

    @Test
    fun all_includesProjectedRangeInXAxis() {
        val trip = Trip(
            startKm = 1000.0,
            points = listOf(Point(0.0, 90.0), Point(20.0, 80.0)),
            avgPctPerKm = 0.5,
        )
        val f = ForecastCalc.compute(trip, route = null)
        val w = ChartWindow.of(f, Zoom.ALL)

        assertEquals(f.live.last().x, w.xMax, 1e-9)
    }
}
