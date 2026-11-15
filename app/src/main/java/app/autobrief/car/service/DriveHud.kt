package app.autobrief.car.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import app.autobrief.car.R
import app.autobrief.car.ui.FAMILY_BADGE
import app.autobrief.car.ui.MainActivity
import app.autobrief.car.ui.ThemeVariant
import app.autobrief.car.ui.km1
import kotlin.math.roundToInt

// Navigation is the main screen of the car: the logbook is written in the background, and while a trip is recorded a
// small status pill over the front app says so. It is a status, not a screen: static, one tap opens the app.

/** What the status says while a trip is recorded; [km] is the distance so far as the home card prints it ("12.0"). */
data class HudState(val km: String, val family: Boolean = false) {
    /** "+12.0 km", the in-trip card's figure. */
    val distance: String get() = "+$km km"
}

/** Why no pill is drawn, in the order the reasons are checked; [reason] is what `HUD_HIDDEN reason=` logs. */
enum class HudHidden(val reason: String) {
    TRIP_ENDED("trip_ended"),
    APP_VISIBLE("app_visible"),
    NO_PERMISSION("no_permission"),
}

/** Null when the pill is drawn: a trip is recorded, the app's own screen is not started, drawing over other apps is allowed. */
fun hudHidden(tripInProgress: Boolean, appVisible: Boolean, canDrawOverlays: Boolean): HudHidden? = when {
    !tripInProgress -> HudHidden.TRIP_ENDED
    appVisible -> HudHidden.APP_VISIBLE
    !canDrawOverlays -> HudHidden.NO_PERMISSION
    else -> null
}

/** The trip being recorded as the status prints it, pill or not (the monitor's notification says it too); null: no trip. */
fun recordingState(tripInProgress: Boolean, distanceKm: Double, family: Boolean = false): HudState? =
    if (tripInProgress) HudState(km1(distanceKm), family) else null

/** The pill to draw, or null for none (see [hudHidden]). The permission is the only switch: there is no setting. */
fun hudState(tripInProgress: Boolean, distanceKm: Double, appVisible: Boolean, canDrawOverlays: Boolean, family: Boolean = false): HudState? =
    recordingState(tripInProgress, distanceKm, family).takeIf { hudHidden(tripInProgress, appVisible, canDrawOverlays) == null }

/** The line of a recorded trip: the label, then the distance ("운행 기록 중 · +12.0 km"). */
fun recordingLine(label: String, distance: String): String = "$label · $distance"

/** The existing wording for a trip being recorded: the home card's, or the family badge of a 가족 운행. */
fun Context.recordingLabel(family: Boolean): String = if (family) FAMILY_BADGE else getString(R.string.recording_trip)

/**
 * The status pill of the monitor service, in a system overlay window (plain Views). The window is added and removed only
 * when [hudState] changes between a pill and none; nothing is built before the first pill is drawn. [log] writes the
 * app's log lines: `HUD_SHOWN km=..` when the pill appears, `HUD_HIDDEN reason=..` when it goes, and once per trip that
 * should have shown it when Android does not allow it (the permission is missing, or the window was refused).
 */
class DriveHud(
    private val context: Context,
    private val log: (String) -> Unit,
    private val canDrawOverlays: () -> Boolean = { Settings.canDrawOverlays(context) },
) {
    private val windows: WindowManager get() = context.getSystemService(WindowManager::class.java)
    private var pill: Pill? = null
    private var shown: HudState? = null

    /** Android refused the window for this trip: not asked again until the trip ends or the app's screen was shown. */
    private var refused = false

    /** `HUD_HIDDEN reason=no_permission` is logged once per trip that should have shown the pill. */
    private var deniedLogged = false

    val isShown: Boolean get() = shown != null

    /** Called when the trip, its printed distance or the app's visibility changes, and on the monitor's tick. */
    fun update(tripInProgress: Boolean, distanceKm: Double, family: Boolean, appVisible: Boolean) {
        val wanted = tripInProgress && !appVisible
        if (!wanted) {
            refused = false
            deniedLogged = false
        }
        // the permission is asked for only when it decides (a binder call), and not again after a refusal
        val allowed = wanted && !refused && canDrawOverlays()
        val state = hudState(tripInProgress, distanceKm, appVisible, allowed, family)
        val was = shown
        when {
            state != null && was == null -> show(state)
            state != null && state != was -> {
                pill?.set(state)
                shown = state
            }
            state == null && was != null -> hide(hudHidden(tripInProgress, appVisible, allowed)!!)
            state == null && wanted && !deniedLogged -> denied()
        }
    }

    /** The service ends: the window goes with it. */
    fun destroy() {
        if (shown != null) hide(HudHidden.TRIP_ENDED)
    }

    private fun show(state: HudState) {
        val p = pill ?: Pill(context).also { pill = it }
        p.set(state)
        try {
            windows.addView(p.view, layoutParams())
        } catch (e: WindowManager.BadTokenException) {
            return refusedBy(e)
        } catch (e: SecurityException) {
            return refusedBy(e)
        }
        shown = state
        log("HUD_SHOWN km=${state.km}" + if (state.family) " family=true" else "")
    }

    private fun hide(why: HudHidden) {
        pill?.let { windows.removeViewImmediate(it.view) }
        shown = null
        if (why == HudHidden.NO_PERMISSION) deniedLogged = true
        log("HUD_HIDDEN reason=${why.reason}")
    }

    /** The platform said no although the permission check passed: said once, no retry loop. */
    private fun refusedBy(e: RuntimeException) {
        refused = true
        denied(e::class.java.simpleName)
    }

    private fun denied(error: String? = null) {
        deniedLogged = true
        log("HUD_HIDDEN reason=${HudHidden.NO_PERMISSION.reason}" + (error?.let { " error=$it" } ?: ""))
    }

    /** Top end, 16dp from the edges, under the status bar (the default system-bar fitting); as large as the pill only. */
    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // never takes focus or keys; touches outside its own bounds go to the app behind
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = context.dp(MARGIN_DP)
        y = context.dp(MARGIN_DP)
        windowAnimations = 0 // static: no enter or exit animation (driver distraction)
        title = WINDOW_TITLE
    }

    /** The pill's Views: a square status pip, the label and the distance, on the B look's deep navy. */
    private class Pill(context: Context) {
        private val label = text(context)
        private val distance = text(context)

        val view: View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(MIN_HEIGHT_DP)
            setPadding(context.dp(PAD_H_DP), context.dp(PAD_V_DP), context.dp(PAD_H_DP), context.dp(PAD_V_DP))
            background = GradientDrawable().apply {
                setColor(COLORS.primary.toArgb()) // ColorRoles.primary of the B look by day: the deep navy
                cornerRadius = context.dpF(SHAPE.cornerControl.value) // ShapeTokens.cornerControl: status pills
            }
            val pip = context.dp(SHAPE.pipSize.value) // ShapeTokens.pipSize: the square status pip
            addView(View(context).apply { setBackgroundColor(COLORS.onPrimary.toArgb()) }, LinearLayout.LayoutParams(pip, pip))
            addView(label, gap(context))
            addView(distance, gap(context))
            isClickable = true
            // the one thing a tap does: open the app
            setOnClickListener { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        fun set(state: HudState) {
            label.text = view.context.recordingLabel(state.family)
            distance.text = state.distance
        }

        private companion object {
            fun text(context: Context) = TextView(context).apply {
                setTextColor(COLORS.onPrimary.toArgb()) // ColorRoles.onPrimary: white on the navy
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP) // TypeScale.label: chips and pills
                typeface = context.resources.getFont(R.font.pretendard_medium) // TypeScale.label's weight in the B look (500)
                includeFontPadding = false
                maxLines = 1 // one static line: never scrolls
            }

            fun gap(context: Context) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = context.dp(GAP_DP) }
        }
    }

    companion object {
        /** The overlay window's title, as `dumpsys window windows` names it. */
        const val WINDOW_TITLE = "AutoBriefHud"

        /** The pill is at least this high (a glance target on the car display). */
        const val MIN_HEIGHT_DP = 56f
        const val MARGIN_DP = 16f
        private const val PAD_H_DP = 20f
        private const val PAD_V_DP = 12f
        private const val GAP_DP = 12f

        // the shipped look's tokens (plain values, read without Compose): deep navy and white, the pill corner, the pip
        private val COLORS get() = ThemeVariant.B.tokens.day
        private val SHAPE get() = ThemeVariant.B.tokens.shape

        /** TypeScale.label of the shipped look; never under the car display's 24sp floor. */
        val TEXT_SP: Float get() = maxOf(24f, ThemeVariant.B.tokens.type.label.fontSize.value)

        private fun Context.dpF(dp: Float): Float = dp * resources.displayMetrics.density
        private fun Context.dp(dp: Float): Int = dpF(dp).roundToInt()
    }
}
