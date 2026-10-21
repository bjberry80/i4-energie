package de.i4energie.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastCalcTest {

    private val tripStart = 50_000.0

    /** Route 216 km, geplant bei Fahrt-km 125, Prognose 66 - km*0.22 in 8-km-Schritten. */
    private fun exampleRoute(startKm: Double = tripStart + 125) = Route(
        lengthKm = 216.0,
        destination = "Ouddorp",
        startKm = startKm,
        profile = (0..216 step 8).map { ProfilePoint(it.toDouble(), 10.0) },
        forecast = (0..216 step 8).map { 66 - it * 0.22 },
    )

    /** Ist alle 10 km mit 98 - km*0.25 bis 230 km. */
    private fun exampleTrip(maxKm: Int = 230) = Trip(
        startKm = tripStart,
        startSoc = 98.0,
        points = (0..maxKm step 10).map { Point(it.toDouble(), 98 - it * 0.25) },
    )

    @Test
    fun example_arrivalAbout11_with15LivePoints() {
        val f = ForecastCalc.compute(exampleTrip(), exampleRoute())

        assertTrue(f.routeValid)
        assertEquals(125.0, f.offset, 1e-9)
        assertEquals(15, f.live.size)
        assertEquals(11.0, f.arrival!!, 1.0)
        assertEquals(Point(230.0, 40.5), f.live.first())
        assertEquals(66 - 216 * 0.22, f.planEnd!!, 1e-9)
        assertEquals(19, f.consumptionDeltaPct)
        assertEquals(341.0, f.xMax, 1e-9)
        assertEquals(111.0, f.remainingKm!!, 1e-9)
    }

    @Test
    fun withoutActualPoints_arrivalIsLastForecast() {
        val f = ForecastCalc.compute(Trip(startKm = null), exampleRoute())

        assertTrue(f.live.isEmpty())
        assertFalse(f.tripRunning)
        assertEquals(66 - 216 * 0.22, f.arrival!!, 1e-9)
        // Ohne Fahrt beginnt die Route bei 0.
        assertEquals(0.0, f.plan.first().x, 1e-9)
    }

    @Test
    fun routeOlderThanTrip_isIgnored() {
        val f = ForecastCalc.compute(exampleTrip(), exampleRoute(startKm = tripStart - 5))

        assertFalse(f.routeValid)
        assertTrue(f.plan.isEmpty())
        assertTrue(f.elevation.isEmpty())
        assertNull(f.arrival)
        assertNull(f.planEnd)
        // Die Route zählt als nicht vorhanden. Statt der Routen-Live-Linie gibt es die
        // Reichweitenprognose aus dem Fahrtverbrauch: 98 -> 40,5 % auf 230 km = 0,25 %/km.
        assertEquals(40.5 / 0.25, f.projectedRangeKm!!, 1e-6)
        assertEquals(2, f.live.size)
        assertEquals(230.0 + 40.5 / 0.25, f.xMax, 1e-6)
    }

    @Test
    fun routePlannedBeforeTrip_startsAtZero() {
        // Route bei Fahrt-km 300 geplant, Fahrt erst bei km 50 -> off > last + 1 -> off = 0
        val f = ForecastCalc.compute(exampleTrip(maxKm = 50), exampleRoute(startKm = tripStart + 300))

        assertTrue(f.routeValid)
        assertEquals(0.0, f.offset, 1e-9)
        assertEquals(0.0, f.plan.first().x, 1e-9)
    }

    @Test
    fun smallExpectedConsumption_factorIsOne() {
        // Ist-Punkte nur knapp hinter dem Routenstart: soll = 66 - P(last) <= 2
        val trip = Trip(
            startKm = tripStart,
            points = listOf(Point(0.0, 98.0), Point(125.0, 70.0), Point(130.0, 50.0)),
        )
        val f = ForecastCalc.compute(trip, exampleRoute())

        assertEquals(1.0, f.factor, 1e-9)
        assertNull(f.consumptionDeltaPct)
        assertTrue(f.live.isNotEmpty())
    }

    @Test
    fun factorIsClamped() {
        // Starker Mehrverbrauch: SOC fällt viel schneller als geplant
        val trip = Trip(
            startKm = tripStart,
            points = listOf(Point(125.0, 66.0), Point(200.0, 6.0)),
        )
        val f = ForecastCalc.compute(trip, exampleRoute())

        assertEquals(2.0, f.factor, 1e-9)
        assertEquals(100, f.consumptionDeltaPct)
    }

    @Test
    fun brokenOrMissingData_noException() {
        ForecastCalc.compute(null, null)
        ForecastCalc.compute(Trip(), Route())
        ForecastCalc.compute(
            Trip(points = listOf(Point(Double.NaN, 1.0), Point(5.0, 80.0))),
            Route(
                startKm = null,
                profile = listOf(ProfilePoint(null, null), ProfilePoint(10.0, null), ProfilePoint(20.0, 5.0)),
                forecast = listOf(null),
            ),
        )
        val f = ForecastCalc.compute(
            exampleTrip(),
            exampleRoute().copy(forecast = emptyList()),
        )
        assertTrue(f.plan.isEmpty())
        assertTrue(f.live.isEmpty())
        assertNull(f.arrival)
        assertTrue(f.elevation.isNotEmpty())
    }

    @Test
    fun interpolate_clampsAndInterpolates() {
        val xs = listOf(0.0, 10.0, 20.0)
        val ys = listOf(100.0, 80.0, 70.0)
        assertEquals(100.0, ForecastCalc.interpolate(xs, ys, -5.0), 1e-9)
        assertEquals(90.0, ForecastCalc.interpolate(xs, ys, 5.0), 1e-9)
        assertEquals(75.0, ForecastCalc.interpolate(xs, ys, 15.0), 1e-9)
        assertEquals(70.0, ForecastCalc.interpolate(xs, ys, 99.0), 1e-9)
    }

    // --- Reichweitenprognose ohne Route (Issue #1) ---

    private fun tripOf(vararg p: Pair<Double, Double>, avg: Double? = null) =
        Trip(startKm = tripStart, points = p.map { Point(it.first, it.second) }, avgPctPerKm = avg)

    @Test
    fun noRoute_blendsLiveAndAverageConsumption() {
        // 20 km gefahren, 10 % verbraucht = 0,5 %/km live; Durchschnitt 0,4 %/km; Gewicht live = 20/30
        val f = ForecastCalc.compute(tripOf(0.0 to 90.0, 20.0 to 80.0, avg = 0.4), route = null)

        val slope = (20.0 / 30.0) * 0.5 + (10.0 / 30.0) * 0.4
        assertEquals(80.0 / slope, f.projectedRangeKm!!, 1e-6)
        assertEquals(2, f.live.size)
        assertEquals(Point(20.0, 80.0), f.live.first())
        assertEquals(20.0 + 80.0 / slope, f.live.last().x, 1e-6)
        assertEquals(0.0, f.live.last().y, 1e-9)
        assertEquals(25, f.consumptionDeltaPct)
        assertNull(f.arrival)
        assertEquals(f.live.last().x, f.xMax, 1e-9)
    }

    @Test
    fun noRoute_shortTripUsesAverageOnly() {
        val f = ForecastCalc.compute(tripOf(0.0 to 90.0, 3.0 to 89.0, avg = 0.4), route = null)

        assertEquals(89.0 / 0.4, f.projectedRangeKm!!, 1e-6)
        assertNull(f.consumptionDeltaPct)
    }

    @Test
    fun noRoute_longTripUsesLiveConsumption() {
        // Ab 30 km zählt nur noch der Fahrtverbrauch: 15 % auf 30 km = 0,5 %/km
        val f = ForecastCalc.compute(tripOf(0.0 to 90.0, 30.0 to 75.0, avg = 0.25), route = null)

        assertEquals(75.0 / 0.5, f.projectedRangeKm!!, 1e-6)
        assertEquals(100, f.consumptionDeltaPct)
    }

    @Test
    fun noRoute_withoutConsumptionDataUsesBmwRange() {
        val f = ForecastCalc.compute(tripOf(0.0 to 80.0), route = null, bmwRangeKm = 400.0)

        assertEquals(400.0, f.projectedRangeKm!!, 1e-6)
        assertEquals(400.0, f.bmwRangeKm!!, 1e-9)
    }

    @Test
    fun noRoute_averageBeatsBmwRange() {
        val f = ForecastCalc.compute(tripOf(0.0 to 80.0, avg = 0.4), route = null, bmwRangeKm = 400.0)

        assertEquals(200.0, f.projectedRangeKm!!, 1e-6)
        assertEquals(400.0, f.bmwRangeKm!!, 1e-9)
    }

    @Test
    fun noRoute_risingSocFallsBackToAverage() {
        // SOC steigt (Rekuperation/Rauschen): kein brauchbarer Fahrtverbrauch
        val f = ForecastCalc.compute(tripOf(0.0 to 50.0, 20.0 to 60.0, avg = 0.5), route = null)

        assertEquals(60.0 / 0.5, f.projectedRangeKm!!, 1e-6)
        assertNull(f.consumptionDeltaPct)
    }

    @Test
    fun noRoute_rangeIsClamped() {
        val f = ForecastCalc.compute(tripOf(0.0 to 80.0, avg = 0.001), route = null)

        assertEquals(ForecastCalc.MAX_RANGE_KM, f.projectedRangeKm!!, 1e-9)
    }

    @Test
    fun noRoute_noUsableDataMeansNoProjection() {
        val f = ForecastCalc.compute(tripOf(0.0 to 80.0), route = null)

        assertNull(f.projectedRangeKm)
        assertTrue(f.live.isEmpty())
        assertNull(f.arrival)
    }

    @Test
    fun noRoute_emptySocMeansNoProjection() {
        val f = ForecastCalc.compute(tripOf(0.0 to 10.0, 20.0 to 0.0, avg = 0.5), route = null)

        assertNull(f.projectedRangeKm)
    }

    @Test
    fun noRoute_withoutTripPointsMeansNoProjection() {
        val f = ForecastCalc.compute(Trip(avgPctPerKm = 0.4), route = null, bmwRangeKm = 300.0)

        assertNull(f.projectedRangeKm)
        assertFalse(f.tripRunning)
    }

    @Test
    fun oldRoute_isTreatedLikeNoRoute() {
        val withRoute = ForecastCalc.compute(exampleTrip(), exampleRoute(startKm = tripStart - 5))
        val noRoute = ForecastCalc.compute(exampleTrip(), route = null)

        assertFalse(withRoute.routeValid)
        assertEquals(noRoute.projectedRangeKm!!, withRoute.projectedRangeKm!!, 1e-9)
        assertEquals(noRoute.live, withRoute.live)
    }

    @Test
    fun validRoute_hasNoRangeProjection() {
        val f = ForecastCalc.compute(exampleTrip().copy(avgPctPerKm = 0.25), exampleRoute(), bmwRangeKm = 300.0)

        assertNull(f.projectedRangeKm)
        assertEquals(11.0, f.arrival!!, 1.0)
        assertEquals(15, f.live.size)
    }
}
