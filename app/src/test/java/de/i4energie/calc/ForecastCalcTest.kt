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
        assertTrue(f.live.isEmpty())
        assertTrue(f.elevation.isEmpty())
        assertNull(f.arrival)
        assertEquals(230.0, f.xMax, 1e-9)
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
}
