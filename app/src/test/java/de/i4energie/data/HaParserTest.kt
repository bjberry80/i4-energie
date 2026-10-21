package de.i4energie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaParserTest {

    @Test
    fun parsesTrip() {
        val json = """
            {"entity_id":"sensor.i4_fahrt","state":"aktiv","attributes":{
              "start_km":"50000.4","start_soc":98,"fahrtverbrauch":null,
              "punkte":[[0,98],[10.5,"95.5"],["x",1],[20]]}}
        """.trimIndent()
        val trip = HaParser.parseTrip(json)!!
        assertEquals(50000.4, trip.startKm!!, 1e-9)
        assertEquals(98.0, trip.startSoc!!, 1e-9)
        assertNull(trip.consumption)
        assertEquals(2, trip.points.size)
        assertEquals(95.5, trip.points[1].y, 1e-9)
    }

    @Test
    fun parsesAverageConsumption() {
        val ok = HaParser.parseTrip("""{"attributes":{"verbrauch_pct_km":"0.2512"}}""")!!
        assertEquals(0.2512, ok.avgPctPerKm!!, 1e-9)
        assertNull(HaParser.parseTrip("""{"attributes":{"verbrauch_pct_km":"unknown"}}""")!!.avgPctPerKm)
        assertNull(HaParser.parseTrip("""{"attributes":{}}""")!!.avgPctPerKm)
    }

    @Test
    fun parsesRoute() {
        val json = """
            {"state":"216.3","attributes":{"ziel":"Ouddorp","aufstieg":410,"abstieg":"395",
              "start_km":50125,"profil":[[0,12,51.8,3.9],[8,"unknown",51.9,4.0]],"prognose":[66,"unavailable"]}}
        """.trimIndent()
        val route = HaParser.parseRoute(json)!!
        assertEquals(216.3, route.lengthKm!!, 1e-9)
        assertEquals("Ouddorp", route.destination)
        assertEquals(395.0, route.descent!!, 1e-9)
        assertEquals(2, route.profile.size)
        assertNull(route.profile[1].elevation)
        assertEquals(listOf(66.0, null), route.forecast)
    }

    @Test
    fun parsesNumericState() {
        assertEquals(38.0, HaParser.parseNumericState("""{"state":"38"}""")!!, 1e-9)
        assertNull(HaParser.parseNumericState("""{"state":"unknown"}"""))
        assertNull(HaParser.parseNumericState("""{"state":"unavailable"}"""))
        assertNull(HaParser.parseNumericState("""{}"""))
    }

    @Test
    fun brokenInput_noException() {
        assertNull(HaParser.parseTrip(null))
        assertNull(HaParser.parseTrip(""))
        assertNull(HaParser.parseTrip("kein json"))
        assertNull(HaParser.parseRoute("[1,2]"))
        assertTrue(HaParser.parseTrip("""{"state":"x"}""")!!.points.isEmpty())
        assertTrue(HaParser.parseTrip("""{"attributes":{"punkte":"unknown"}}""")!!.points.isEmpty())
        assertTrue(HaParser.parseTrip("""{"attributes":{"punkte":"[[1,2]]"}}""")!!.points.size == 1)
        val route = HaParser.parseRoute("""{"state":null,"attributes":{"profil":{"a":1},"prognose":5,"ziel":null}}""")!!
        assertTrue(route.profile.isEmpty())
        assertTrue(route.forecast.isEmpty())
        assertNull(route.destination)
        assertNull(route.lengthKm)
    }
}
