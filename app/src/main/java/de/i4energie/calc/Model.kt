package de.i4energie.calc

/** Ein Punkt im Diagramm: x = km seit Fahrtbeginn, y = SOC in %. */
data class Point(val x: Double, val y: Double)

/** Inhalt von `sensor.i4_fahrt`. */
data class Trip(
    val startKm: Double? = null,
    val startSoc: Double? = null,
    /** `[km_seit_start, soc]`, chronologisch. */
    val points: List<Point> = emptyList(),
    val consumption: Double? = null,
    /** Durchschnittsverbrauch in % SOC pro km (`verbrauch_pct_km`), aus der Fahrzeug-Statistik. */
    val avgPctPerKm: Double? = null,
)

/** Ein Eintrag aus `profil`: `[km, höhe_m, lat, lon]`. Ungültige Werte sind `null`. */
data class ProfilePoint(val km: Double?, val elevation: Double?)

/** Inhalt von `sensor.i4_route`. */
data class Route(
    val lengthKm: Double? = null,
    val destination: String? = null,
    val ascent: Double? = null,
    val descent: Double? = null,
    val startKm: Double? = null,
    val profile: List<ProfilePoint> = emptyList(),
    /** SOC-Prognose, gleiche Länge wie [profile]. Ungültige Werte sind `null`. */
    val forecast: List<Double?> = emptyList(),
)

/** Alle HA-Werte eines Abrufs. */
data class TripData(
    val trip: Trip? = null,
    val route: Route? = null,
    val socNow: Double? = null,
    val bmwArrivalSoc: Double? = null,
    /** Restreichweite laut Fahrzeug in km (`range_ev_remaining_range`). */
    val bmwRangeKm: Double? = null,
)
