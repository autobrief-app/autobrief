package app.autobrief.car

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.LocalThemeVariant
import app.autobrief.car.ui.Tab
import app.autobrief.car.ui.ThemeVariant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The floors the proposal states for a car display, kept in every look: text at least 24sp, touch targets at least 76dp.
 * And the report preview's wording, which follows from how many whole rows fit under the panels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
class LookFloorsTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    /** The documents' sample year, parked, with the car and navigation connected. */
    private fun sample(): Harness {
        val h = DocsSample.build(webhook = "https://fleet.example.com/hook")
        h.core.onVehicle(VehicleEvent.Connection(true))
        h.core.onNavi(NaviEvent.Ready(true))
        return h
    }

    @Test fun everyTextStyleIsAtLeast24sp() {
        for (variant in ThemeVariant.values()) {
            val t = variant.tokens.type
            val styles = mapOf(
                "display" to t.display, "headline" to t.headline, "title" to t.title, "body" to t.body,
                "bodyStrong" to t.bodyStrong, "caption" to t.caption, "label" to t.label, "glance" to t.glance,
            )
            for ((name, style) in styles) {
                assertTrue("$variant $name is ${style.fontSize}", style.fontSize.isSp && style.fontSize.value >= 24f)
            }
        }
    }

    @Test fun everyTouchTargetIsAtLeast76dp() {
        val core = sample().core
        val tab = mutableStateOf(Tab.HOME)
        val look = mutableStateOf(ThemeVariant.BUILD)
        compose.setContent {
            CompositionLocalProvider(LocalThemeVariant provides look.value) { AutoBriefScreen(core, tab, onFlush = {}, night = true) }
        }
        val floor = with(compose.density) { 76.dp.roundToPx() }
        for (variant in ThemeVariant.values()) {
            for (t in Tab.values()) {
                look.value = variant
                tab.value = t
                compose.waitForIdle()
                val small = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
                    .filter { it.size.width < floor || it.size.height < floor }
                    .map { "${it.config.getOrNull(SemanticsProperties.Text)?.joinToString()} ${it.size}" }
                assertTrue("$variant $t has touch targets under 76dp: $small", small.isEmpty())
            }
        }
    }

    @Test fun reportPreviewShowsFiveRowsOfTheSampleYear() {
        val core = sample().core
        compose.setContent { AutoBriefScreen(core, mutableStateOf(Tab.REPORT), onFlush = {}, night = true) }
        compose.waitForIdle()
        compose.onNodeWithText("미리보기 (처음 5행)").assertExists()
        compose.onNodeWithText("나머지 482행은 엑셀 파일에서 볼 수 있습니다.").assertExists()
    }
}
