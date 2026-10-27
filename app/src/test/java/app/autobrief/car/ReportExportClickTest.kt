package app.autobrief.car

import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 엑셀 만들기 on the real report screen, with the app's own threading (the main looper, a build thread, an I/O thread).
 * The rehearsal's business-year workbook took 5.8 s inside this click handler on a loaded two-core emulator and Android
 * closed the app (5 s input limit). Now the click only asks for the workbook: it returns before the workbook is
 * written, the screen says '만드는 중…' with both buttons off, and the main thread never writes a file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
class ReportExportClickTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val workThread = Executors.newSingleThreadExecutor { r -> Thread(r, "wb-work").apply { isDaemon = true } }
    private val ioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "wb-io").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @After fun stop() {
        scope.cancel()
        workThread.shutdownNow()
        ioThread.shutdownNow()
    }

    @Test fun theClickReturnsBeforeTheWorkbookIsWrittenAndTheMainThreadWritesNothing() {
        val main = Looper.getMainLooper().thread.name
        val writers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val release = CountDownLatch(1)
        val h = Harness(
            startAt = Harness.at(LocalDate.of(2026, 10, 23), 9, 0),
            background = AutoBriefCore.Background(scope, workThread.asCoroutineDispatcher(), ioThread.asCoroutineDispatcher()),
            // each file write waits for the test (at most 5 s): a write made inside the click would hold the click that long;
            // the thread name loses the " @coroutine#n" that coroutine debug mode appends
            onWrite = { writers += Thread.currentThread().name.substringBefore(" @"); release.await(5, TimeUnit.SECONDS) },
        )
        check(h.core.setDemoMode(true, null) == AutoBriefCore.SettingsResult.SAVED)
        h.core.loadSampleHistory("http://10.0.2.2:8787/hook") // an address, so 지금 보내기 is on too
        compose.setContent { AutoBriefScreen(h.core, mutableStateOf(Tab.REPORT), onFlush = {}, night = true) }
        compose.waitForIdle()
        compose.onNode(button and hasText("지금 보내기")).assertIsEnabled()

        compose.onNode(button and hasText("엑셀 만들기")).performClick()
        compose.waitForIdle()
        // back from the click with the workbook not written: the screen says so and neither button takes a second tap
        assertTrue("written on the main thread: $writers", writers.none { it == main })
        compose.onNodeWithText("만드는 중…").assertExists()
        compose.onNode(button and hasText("엑셀 만들기")).assertIsNotEnabled()
        compose.onNode(button and hasText("지금 보내기")).assertIsNotEnabled()

        release.countDown()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("차량에 저장했습니다", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("차량에 저장했습니다: 운행기록부_2026-01-01_2026-12-31.xlsx", substring = true).assertExists()
        compose.onNode(button and hasText("엑셀 만들기")).assertIsEnabled()
        assertEquals("the .xlsx and the .csv, on the I/O thread", listOf("wb-io", "wb-io"), writers.toList())
    }
}
