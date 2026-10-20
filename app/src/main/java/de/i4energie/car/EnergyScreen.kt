package de.i4energie.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.i4energie.R
import de.i4energie.data.HaRepository
import de.i4energie.data.RepoState
import kotlinx.coroutines.launch

/**
 * Zeigt ein [NavigationTemplate] mit Action Strip und zeichnet das Diagramm selbst auf die Fläche.
 * Es wird keine Zielführung gestartet (kein `navigationStarted()`).
 */
class EnergyScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = ChartRenderer(carContext)
    private var surfaceContainer: SurfaceContainer? = null
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var repoState: RepoState = HaRepository.state.value

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surfaceContainer = container
            render()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            surfaceContainer = null
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = Rect(area)
            render()
        }

        override fun onStableAreaChanged(area: Rect) {
            stableArea = Rect(area)
            render()
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = HaRepository.startPolling()
            override fun onStop(owner: LifecycleOwner) = HaRepository.stopPolling()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HaRepository.state.collect {
                    repoState = it
                    render()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val refresh = Action.Builder()
            .setIcon(icon(R.drawable.ic_refresh))
            .setOnClickListener { HaRepository.refresh() }
            .build()
        val settings = Action.Builder()
            .setIcon(icon(R.drawable.ic_settings))
            .setOnClickListener {
                CarToast.makeText(carContext, R.string.car_settings_hint, CarToast.LENGTH_LONG).show()
            }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(refresh).addAction(settings).build())
            .build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun render() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return
        val area = stableArea ?: visibleArea ?: Rect(0, 0, container.width, container.height)
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas fehlgeschlagen", e)
            return
        }
        try {
            renderer.draw(canvas, area, container.dpi, repoState, System.currentTimeMillis())
        } catch (e: Exception) {
            // Nie abstürzen: lieber einen leeren Frame zeigen.
            Log.e(TAG, "Zeichnen fehlgeschlagen", e)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val TAG = "EnergyScreen"
    }
}
