package de.i4energie.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
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
import de.i4energie.calc.Zoom
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
    private var zoom = Zoom.ALL

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
        // Die Action Strip der Navigationsvorlage erlaubt höchstens 4 Buttons.
        val strip = ActionStrip.Builder()
            .addAction(zoomAction(Zoom.KM30, R.string.zoom_30))
            .addAction(zoomAction(Zoom.KM100, R.string.zoom_100))
            .addAction(zoomAction(Zoom.ALL, R.string.zoom_all))
            .addAction(refresh)
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    /** Button für einen Streckenausschnitt. Der gewählte Ausschnitt ist als primäre Aktion hervorgehoben. */
    private fun zoomAction(target: Zoom, title: Int): Action {
        val builder = Action.Builder()
            .setTitle(carContext.getString(title))
            .setOnClickListener {
                if (zoom != target) {
                    zoom = target
                    invalidate()
                    render()
                }
            }
        if (zoom == target && carContext.carAppApiLevel >= 2) builder.setFlags(Action.FLAG_PRIMARY)
        return builder.build()
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
            renderer.draw(canvas, area, container.dpi, repoState, System.currentTimeMillis(), zoom)
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
