package de.i4energie.data

import android.content.Context
import de.i4energie.calc.TripData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RepoState(
    val configured: Boolean = false,
    /** Letzte erfolgreich geladene Daten; bleiben bei Fehlern erhalten. */
    val data: TripData? = null,
    /** Zeitpunkt des letzten erfolgreichen Abrufs (epoch ms). */
    val lastSuccess: Long? = null,
    val lastAttempt: Long? = null,
    /** Fehler des letzten Abrufs, `null` wenn er erfolgreich war. */
    val error: String? = null,
)

/**
 * Holt die HA-Sensoren und hält den letzten Stand. Wird von Phone-UI und Car-Session gemeinsam genutzt.
 */
object HaRepository {

    const val POLL_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchMutex = Mutex()
    private val _state = MutableStateFlow(RepoState())
    val state: StateFlow<RepoState> = _state.asStateFlow()

    private lateinit var settingsStore: SettingsStore
    private var pollJob: Job? = null
    private var pollUsers = 0

    fun init(context: Context) {
        if (!::settingsStore.isInitialized) {
            settingsStore = SettingsStore(context)
            _state.update { it.copy(configured = settingsStore.load().isComplete) }
        }
    }

    fun settings(): SettingsStore = settingsStore

    /** Nach dem Speichern neuer Einstellungen aufrufen. */
    fun onSettingsChanged() {
        _state.update { RepoState(configured = settingsStore.load().isComplete) }
        if (pollJob?.isActive == true) refresh()
    }

    /** Startet das Polling (sofort + alle 30 s). Aufrufe werden gezählt. */
    @Synchronized
    fun startPolling() {
        pollUsers++
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                fetch()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stopPolling() {
        pollUsers = (pollUsers - 1).coerceAtLeast(0)
        if (pollUsers == 0) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** Einmaliger Abruf, z. B. bei Tipp auf „Aktualisieren“. */
    fun refresh() {
        scope.launch { fetch() }
    }

    private suspend fun fetch() = fetchMutex.withLock {
        val settings = settingsStore.load()
        if (!settings.isComplete) {
            _state.update { it.copy(configured = false) }
            return@withLock
        }
        val now = System.currentTimeMillis()
        try {
            val client = HaClient(settings.baseUrl, settings.token)
            val data = coroutineScope {
                val trip = async { client.state(HaParser.ENTITY_TRIP) }
                val route = async { client.state(HaParser.ENTITY_ROUTE) }
                val soc = async { client.state(HaParser.ENTITY_SOC) }
                val bmw = async { client.state(HaParser.ENTITY_BMW_ARRIVAL) }
                val range = async { client.state(HaParser.ENTITY_RANGE) }
                TripData(
                    trip = HaParser.parseTrip(trip.await()),
                    route = HaParser.parseRoute(route.await()),
                    socNow = HaParser.parseNumericState(soc.await()),
                    bmwArrivalSoc = HaParser.parseNumericState(bmw.await()),
                    bmwRangeKm = HaParser.parseNumericState(range.await()),
                )
            }
            _state.update {
                it.copy(configured = true, data = data, lastSuccess = now, lastAttempt = now, error = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = (e as? HaException)?.message ?: e.javaClass.simpleName
            _state.update { it.copy(configured = true, lastAttempt = now, error = msg) }
        }
    }
}
