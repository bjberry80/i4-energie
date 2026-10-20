package de.i4energie.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import de.i4energie.R
import de.i4energie.calc.Forecast
import de.i4energie.calc.ForecastCalc
import de.i4energie.calc.Point
import de.i4energie.calc.TripData
import de.i4energie.data.RepoState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Zeichnet „verbleibende Energie über Strecke“ auf ein [Canvas]. Keine Animationen.
 * Alle Größen in dp, skaliert mit der dpi der Auto-Fläche.
 */
class ChartRenderer(private val context: Context) {

    private var density = 0f

    private val titlePaint = textPaint(Color.WHITE, bold = true)
    private val infoPaint = textPaint(COLOR_INFO)
    private val infoSmallPaint = textPaint(COLOR_INFO)
    private val arrivalPaint = textPaint(COLOR_LIVE, bold = true).apply { textAlign = Paint.Align.RIGHT }
    private val subPaint = textPaint(COLOR_AXIS).apply { textAlign = Paint.Align.RIGHT }
    private val stalePaint = textPaint(COLOR_PLAN)
    private val axisPaint = textPaint(COLOR_AXIS)
    private val legendPaint = textPaint(COLOR_INFO)
    private val messagePaint = textPaint(Color.WHITE, bold = true)
    private val messageSubPaint = textPaint(COLOR_AXIS)

    private val gridPaint = stroke(COLOR_GRID)
    private val elevationFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb((0.18 * 255).roundToInt(), 158, 158, 158)
    }
    private val elevationLine = stroke(Color.argb((0.6 * 255).roundToInt(), 158, 158, 158))
    private val bmwPaint = stroke(COLOR_BMW).apply { strokeCap = Paint.Cap.ROUND }
    private val planPaint = stroke(COLOR_PLAN)
    private val actualPaint = stroke(COLOR_ACTUAL).apply { strokeJoin = Paint.Join.MITER }
    private val livePaint = stroke(COLOR_LIVE).apply { strokeCap = Paint.Cap.ROUND }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ACTUAL }
    private val markerStroke = stroke(Color.WHITE)
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

    fun draw(canvas: Canvas, area: Rect, dpi: Int, state: RepoState, now: Long) {
        applyDensity(if (dpi > 0) dpi / 160f else context.resources.displayMetrics.density)
        canvas.drawColor(COLOR_BG)
        val box = RectF(area)
        val data = state.data
        when {
            !state.configured -> message(canvas, box, str(R.string.car_not_configured), null)
            data == null && state.error != null ->
                message(canvas, box, str(R.string.car_no_connection), stand(state.lastSuccess ?: state.lastAttempt))
            data == null -> message(canvas, box, str(R.string.car_loading), null)
            else -> drawContent(canvas, box, data, if (state.error != null) stand(state.lastSuccess) else null)
        }
    }

    // --- Inhalt ---

    private fun drawContent(canvas: Canvas, area: RectF, data: TripData, staleText: String?) {
        val f = ForecastCalc.compute(data.trip, data.route)
        val bmw = data.bmwArrivalSoc?.takeIf { f.tripRunning && it in 0.0..100.0 }

        val pad = dp(16f)
        val legend = buildList {
            if (f.tripRunning) add(R.string.legend_ist to COLOR_ACTUAL)
            if (f.live.isNotEmpty()) add(R.string.legend_live to COLOR_LIVE)
            if (f.plan.isNotEmpty()) add(R.string.legend_plan to COLOR_PLAN)
            if (bmw != null) add(R.string.legend_bmw to COLOR_BMW)
        }
        val swatchW = dp(24f)
        val legendW = if (legend.isEmpty()) 0f else
            dp(16f) + swatchW + dp(8f) + legend.maxOf { legendPaint.measureText(str(it.first)) }
        val rightLabelW = axisPaint.measureText("100 %") + dp(12f)
        val leftLabelW = axisPaint.measureText("1000 m") + dp(12f)
        val xLabelH = axisPaint.fontSpacing + dp(8f)

        val plot = RectF(
            area.left + pad + leftLabelW,
            0f,
            area.right - pad - legendW - rightLabelW,
            area.bottom - pad - xLabelH,
        )
        val headerRight = plot.right + rightLabelW
        val headerBottom = drawHeader(canvas, f, data, bmw, staleText, plot.left, area.top + pad, headerRight)
        plot.top = headerBottom + dp(20f)

        if (plot.height() < dp(80f) || plot.width() < dp(120f)) return

        val sx = { km: Double -> (plot.left + km / f.xMax * plot.width()).toFloat() }
        val sy = { soc: Double -> (plot.bottom - soc / 100.0 * plot.height()).toFloat() }

        drawSocGrid(canvas, plot, sy)
        drawXAxis(canvas, plot, f.xMax, sx, area.right - pad)
        drawElevation(canvas, plot, f.elevation, sx)

        canvas.save()
        canvas.clipRect(plot.left - dp(2f), plot.top - dp(4f), plot.right + dp(2f), plot.bottom + dp(4f))
        if (bmw != null) {
            val y = sy(bmw)
            canvas.drawLine(plot.left, y, plot.right, y, bmwPaint)
        }
        if (f.plan.size >= 2) canvas.drawPath(linePath(f.plan, sx, sy), planPaint)
        if (f.actual.size >= 2) canvas.drawPath(stairPath(f.actual, sx, sy), actualPaint)
        if (f.live.size >= 2) canvas.drawPath(linePath(f.live, sx, sy), livePaint)
        canvas.restore()

        f.actual.lastOrNull()?.let {
            val cx = sx(it.x)
            val cy = sy(it.y.coerceIn(0.0, 100.0))
            canvas.drawCircle(cx, cy, dp(10f), markerFill)
            canvas.drawCircle(cx, cy, dp(10f), markerStroke)
        }

        drawLegend(canvas, legend, area.right - pad - legendW + dp(16f), plot.centerY(), swatchW)
    }

    /** Zeichnet die Kopfzeile und liefert ihre Unterkante. */
    private fun drawHeader(
        canvas: Canvas,
        f: Forecast,
        data: TripData,
        bmw: Double?,
        staleText: String?,
        left: Float,
        top: Float,
        right: Float,
    ): Float {
        // Rechts: Ankunft + Detailzeile
        var rightW = 0f
        var rightBottom = top
        val arrival = f.arrival
        if (arrival != null) {
            val arrivalText = str(R.string.car_arrival, arrival.roundToInt())
            val arrivalBase = top - arrivalPaint.fontMetrics.ascent * 0.9f
            canvas.drawText(arrivalText, right, arrivalBase, arrivalPaint)
            rightW = arrivalPaint.measureText(arrivalText)
            rightBottom = arrivalBase + arrivalPaint.descent()

            val sub = listOfNotNull(
                f.planEnd?.takeIf { f.live.isNotEmpty() }?.let { str(R.string.car_plan, it.roundToInt()) },
                bmw?.let { str(R.string.car_bmw, it.roundToInt()) },
                f.consumptionDeltaPct?.let { str(R.string.car_consumption, signed(it)) },
            ).joinToString(SEP)
            if (sub.isNotEmpty()) {
                val subBase = rightBottom + dp(2f) - subPaint.ascent()
                canvas.drawText(sub, right, subBase, subPaint)
                rightW = max(rightW, subPaint.measureText(sub))
                rightBottom = subBase + subPaint.descent()
            }
        }

        // Links: Ziel + Infos
        val maxX = right - rightW - dp(24f)
        val titleBase = top - titlePaint.ascent() + dp(4f)
        var x = left
        val title = if (f.routeValid) data.route?.destination.orEmpty() else str(R.string.car_no_route)
        if (title.isNotEmpty() && maxX > x) {
            val shown = ellipsize(title, titlePaint, maxX - x)
            canvas.drawText(shown, x, titleBase, titlePaint)
            x += titlePaint.measureText(shown) + dp(20f)
        }

        val socNow = data.socNow ?: f.actual.lastOrNull()?.y
        val route = data.route
        val remaining = f.remainingKm?.takeIf { f.routeValid }?.let { str(R.string.car_remaining, it.roundToInt()) }
        val up = route?.ascent
        val down = route?.descent
        val upDown = if (f.routeValid && up != null && down != null) {
            str(R.string.car_up_down, up.roundToInt(), down.roundToInt())
        } else null
        val ist = socNow?.let { str(R.string.car_ist, it.roundToInt()) }
        val candidates = listOf(
            listOfNotNull(remaining, upDown, ist),
            listOfNotNull(remaining, ist),
            listOfNotNull(ist),
        )
        val texts = candidates.map { it.joinToString(SEP) }.filter { it.isNotEmpty() }
        var leftBottom = titleBase + titlePaint.descent()
        // Passt die Info nicht neben das Ziel, kommt sie kleiner in eine zweite Zeile.
        val sameLine = texts.firstOrNull { infoPaint.measureText(it) <= maxX - x }
        if (sameLine != null) {
            canvas.drawText(sameLine, x, titleBase, infoPaint)
        } else if (texts.isNotEmpty() && maxX > left) {
            val avail = maxX - left
            val info = texts.firstOrNull { infoSmallPaint.measureText(it) <= avail }
                ?: ellipsize(texts.last(), infoSmallPaint, avail)
            val infoBase = leftBottom + dp(4f) - infoSmallPaint.ascent()
            canvas.drawText(info, left, infoBase, infoSmallPaint)
            leftBottom = infoBase + infoSmallPaint.descent()
        }

        if (staleText != null) {
            val staleBase = leftBottom + dp(4f) - stalePaint.ascent()
            canvas.drawText(staleText, left, staleBase, stalePaint)
            leftBottom = staleBase + stalePaint.descent()
        }
        return max(leftBottom, rightBottom)
    }

    private fun drawSocGrid(canvas: Canvas, plot: RectF, sy: (Double) -> Float) {
        val textOffset = -(axisPaint.ascent() + axisPaint.descent()) / 2
        for (soc in 0..100 step 20) {
            val y = sy(soc.toDouble())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText("$soc %", plot.right + dp(12f), y + textOffset, axisPaint)
        }
    }

    private fun drawXAxis(canvas: Canvas, plot: RectF, xMax: Double, sx: (Double) -> Float, maxRight: Float) {
        // Spec: alle 50 km (unter 100 km alle 10 km); bei wenig Platz größere Schritte.
        val labelW = axisPaint.measureText("${xMax.roundToInt()} km") + dp(24f)
        val maxLabels = max(2, (plot.width() / labelW).toInt())
        val minStep = if (xMax < 100) 10 else 50
        val step = listOf(10, 20, 50, 100, 200, 500, 1000)
            .firstOrNull { it >= minStep && xMax / it + 1 <= maxLabels } ?: 1000
        val base = plot.bottom + dp(8f) - axisPaint.ascent()
        var lastRight = Float.NEGATIVE_INFINITY
        var km = 0
        while (km <= xMax + 1e-6) {
            val label = "$km km"
            val w = axisPaint.measureText(label)
            val cx = sx(km.toDouble())
            val l = (cx - w / 2).coerceAtMost(maxRight - w)
            if (l > lastRight + dp(12f)) {
                canvas.drawText(label, l, base, axisPaint)
                lastRight = l + w
            }
            km += step
        }
    }

    private fun drawElevation(canvas: Canvas, plot: RectF, elevation: List<Point>, sx: (Double) -> Float) {
        if (elevation.size < 2) return
        val hMin = elevation.minOf { it.y }
        val hMax = elevation.maxOf { it.y }
        val span = max(hMax - hMin, 20.0)
        val lo = hMin - span * 0.1
        val hi = lo + (hMax - lo) / 0.6 // Höhe nutzt etwa die unteren 60 %
        val hy = { h: Double -> (plot.bottom - (h - lo) / (hi - lo) * plot.height()).toFloat() }

        // Linke Achse: nur Beschriftung, keine Gitterlinien
        val step = niceStep((hMax - lo) / 3)
        val textOffset = -(axisPaint.ascent() + axisPaint.descent()) / 2
        axisPaint.textAlign = Paint.Align.RIGHT
        var h = ceil(lo / step) * step
        while (h <= hMax + step * 0.01) {
            val y = hy(h)
            if (y >= plot.top && y <= plot.bottom) {
                canvas.drawText("${h.roundToInt()} m", plot.left - dp(12f), y + textOffset, axisPaint)
            }
            h += step
        }
        axisPaint.textAlign = Paint.Align.LEFT

        val outline = Path()
        elevation.forEachIndexed { i, p ->
            val x = sx(p.x)
            val y = hy(p.y)
            if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        val fill = Path(outline).apply {
            lineTo(sx(elevation.last().x), plot.bottom)
            lineTo(sx(elevation.first().x), plot.bottom)
            close()
        }
        canvas.save()
        canvas.clipRect(plot)
        canvas.drawPath(fill, elevationFill)
        canvas.drawPath(outline, elevationLine)
        canvas.restore()
    }

    private fun drawLegend(canvas: Canvas, items: List<Pair<Int, Int>>, left: Float, centerY: Float, swatchW: Float) {
        if (items.isEmpty()) return
        val row = legendPaint.fontSpacing * 1.4f
        val textOffset = -(legendPaint.ascent() + legendPaint.descent()) / 2
        var y = centerY - row * (items.size - 1) / 2
        for ((label, color) in items) {
            swatchPaint.color = color
            canvas.drawRect(left, y - dp(4f), left + swatchW, y + dp(4f), swatchPaint)
            canvas.drawText(str(label), left + swatchW + dp(8f), y + textOffset, legendPaint)
            y += row
        }
    }

    /** Zentrierte Meldung; lange Texte brechen um statt abgeschnitten zu werden. */
    private fun message(canvas: Canvas, area: RectF, title: String, sub: String?) {
        val maxW = max(area.width() - dp(64f), dp(100f)).toInt()
        val titleLayout = centeredLayout(title, messagePaint, maxW)
        val subLayout = sub?.let { centeredLayout(it, messageSubPaint, maxW) }
        val gap = if (subLayout != null) dp(8f) else 0f
        val total = titleLayout.height + gap + (subLayout?.height ?: 0)
        canvas.save()
        canvas.translate(area.centerX() - maxW / 2f, area.centerY() - total / 2f)
        titleLayout.draw(canvas)
        if (subLayout != null) {
            canvas.translate(0f, titleLayout.height + gap)
            subLayout.draw(canvas)
        }
        canvas.restore()
    }

    private fun centeredLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    // --- Pfade ---

    private fun linePath(points: List<Point>, sx: (Double) -> Float, sy: (Double) -> Float) = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(sx(p.x), sy(p.y)) else lineTo(sx(p.x), sy(p.y)) }
    }

    /** Treppenform: erst horizontal, dann vertikal. */
    private fun stairPath(points: List<Point>, sx: (Double) -> Float, sy: (Double) -> Float) = Path().apply {
        moveTo(sx(points[0].x), sy(points[0].y))
        for (i in 1 until points.size) {
            lineTo(sx(points[i].x), sy(points[i - 1].y))
            lineTo(sx(points[i].x), sy(points[i].y))
        }
    }

    // --- Hilfsfunktionen ---

    private fun applyDensity(d: Float) {
        if (d == density) return
        density = d
        titlePaint.textSize = dp(32f)
        infoPaint.textSize = dp(32f)
        infoSmallPaint.textSize = dp(24f)
        arrivalPaint.textSize = dp(40f)
        subPaint.textSize = dp(24f)
        stalePaint.textSize = dp(24f)
        axisPaint.textSize = dp(20f)
        legendPaint.textSize = dp(20f)
        messagePaint.textSize = dp(32f)
        messageSubPaint.textSize = dp(24f)

        gridPaint.strokeWidth = dp(1f)
        elevationLine.strokeWidth = dp(1.5f)
        bmwPaint.strokeWidth = dp(3f)
        bmwPaint.pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(8f)), 0f)
        planPaint.strokeWidth = dp(4f)
        planPaint.pathEffect = DashPathEffect(floatArrayOf(dp(14f), dp(10f)), 0f)
        actualPaint.strokeWidth = dp(6f)
        livePaint.strokeWidth = dp(5f)
        livePaint.pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(11f)), 0f)
        markerStroke.strokeWidth = dp(3f)
    }

    private fun dp(v: Float) = v * density

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun stand(time: Long?) = time?.let { str(R.string.car_stand, timeFormat.format(Date(it))) }

    private fun signed(v: Int) = when {
        v > 0 -> "+$v"
        v < 0 -> "−${abs(v)}"
        else -> "±0"
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, max(width, 0f), TextUtils.TruncateAt.END).toString()

    private fun niceStep(raw: Double): Double {
        if (raw <= 0 || !raw.isFinite()) return 10.0
        val exp = 10.0.pow(floor(log10(raw)))
        val n = raw / exp
        val nice = when {
            n <= 1 -> 1.0
            n <= 2 -> 2.0
            n <= 5 -> 5.0
            else -> 10.0
        }
        return nice * exp
    }

    private companion object {
        const val SEP = "  ·  "
        val COLOR_BG = Color.parseColor("#121212")
        val COLOR_GRID = Color.parseColor("#2a2a2a")
        val COLOR_AXIS = Color.parseColor("#9e9e9e")
        val COLOR_INFO = Color.parseColor("#d0d0d0")
        val COLOR_ACTUAL = Color.parseColor("#4caf50")
        val COLOR_LIVE = Color.parseColor("#a5d6a7")
        val COLOR_PLAN = Color.parseColor("#ff9800")
        val COLOR_BMW = Color.parseColor("#2196f3")

        fun textPaint(color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
        }
    }
}
