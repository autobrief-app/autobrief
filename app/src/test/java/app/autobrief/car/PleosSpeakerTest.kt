package app.autobrief.car

import android.content.Context
import android.os.Looper
import app.autobrief.car.notify.PleosSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Pleos TextToSpeech wrapper: connects at the first line, speaks the latest line once the service is ready, and
 * stays silent (then tries again) where the service refuses. Robolectric has no Speech Service, so a fake client
 * stands in for the SDK's calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PleosSpeakerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class FakeClient : PleosSpeaker.SpeechClient {
        val spoken = mutableListOf<String>()
        var released = false
        var onReady: (() -> Unit)? = null
        var onFailure: ((String) -> Unit)? = null
        override fun initialize(onReady: () -> Unit, onFailure: (String) -> Unit) {
            this.onReady = onReady
            this.onFailure = onFailure
        }

        override fun speak(text: String, onEvent: (String) -> Unit) {
            spoken += text
            onEvent("start")
            onEvent("done")
        }

        override fun release() { released = true }
    }

    private val clients = mutableListOf<FakeClient>()
    private fun speaker(): PleosSpeaker {
        val connect: (Context) -> PleosSpeaker.SpeechClient = { FakeClient().also { clients += it } }
        return PleosSpeaker(app, connect)
    }

    @Test fun nothingIsConnectedUntilTheFirstLine() {
        speaker()
        assertTrue(clients.isEmpty())
    }

    @Test fun theLineWaitsForTheServiceAndOnlyTheLatestIsSpoken() {
        val s = speaker()
        s.say("출퇴근으로 기록했습니다. 18킬로미터입니다.")
        s.say("일반 업무로 기록했습니다. 11킬로미터입니다.")
        val sdk = clients.single()
        assertTrue("not spoken before the service is ready", sdk.spoken.isEmpty())
        sdk.onReady!!()
        idle()
        assertEquals(listOf("일반 업무로 기록했습니다. 11킬로미터입니다."), sdk.spoken)
        s.say("비업무로 기록했습니다. 3킬로미터입니다.")
        assertEquals("one connection is reused", 1, clients.size)
        assertEquals(2, sdk.spoken.size)
    }

    @Test fun aRefusingServiceDropsTheLineAndTheNextLineTriesAgain() {
        val s = speaker()
        s.say("출퇴근으로 기록했습니다. 18킬로미터입니다.")
        val first = clients.single()
        first.onFailure!!("PERMISSION_DENIED")
        idle()
        assertTrue(first.released)
        assertTrue(first.spoken.isEmpty())
        s.say("일반 업무로 기록했습니다. 11킬로미터입니다.")
        assertEquals("a new connection for the next line", 2, clients.size)
        clients.last().onReady!!()
        idle()
        assertEquals(listOf("일반 업무로 기록했습니다. 11킬로미터입니다."), clients.last().spoken)
        assertTrue("the refused line is not replayed", first.spoken.isEmpty())
    }

    @Test fun releaseClosesTheConnectionAndALateReadyIsIgnored() {
        val s = speaker()
        s.say("출퇴근으로 기록했습니다. 18킬로미터입니다.")
        val sdk = clients.single()
        s.release()
        assertTrue(sdk.released)
        sdk.onReady!!()
        idle()
        assertTrue("nothing is spoken on a released connection", sdk.spoken.isEmpty())
    }
}
