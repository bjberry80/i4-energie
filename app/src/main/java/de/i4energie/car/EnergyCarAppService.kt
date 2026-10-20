package de.i4energie.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import de.i4energie.BuildConfig
import de.i4energie.data.HaRepository

/** Einstieg für Android Auto. Registriert als Navigations-App, damit die Fläche während der Fahrt sichtbar bleibt. */
class EnergyCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                // Android Auto wurde auf einen neuen Signaturschlüssel umgestellt, der in der
                // Beispielliste der Bibliothek 1.7.0 noch fehlt (Stand 10/2026).
                .addAllowedHost(GEARHEAD, GEARHEAD_SHA256_2026)
                .build()
        }

    private companion object {
        const val GEARHEAD = "com.google.android.projection.gearhead"
        const val GEARHEAD_SHA256_2026 = "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = EnergySession()
}

class EnergySession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        HaRepository.init(carContext)
        return EnergyScreen(carContext)
    }
}
