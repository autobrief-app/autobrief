package app.autobrief.car.notify

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Speaks one line through the Pleos TextToSpeech SDK (Pleos Connect's Speech Service, permission
 * `pleos.car.permission.TTS_SERVICE`), on-device mode: no network and no app registration.
 *
 * The SDK is connected at the first line, not at start, so a car that never closes a trip never binds it. While it
 * connects the latest line waits and older ones are dropped (a parked driver wants the last trip, not a queue). Where
 * the service refuses, the line is dropped, the reason logged, and the next line tries a new connection.
 */
class PleosSpeaker(context: Context, private val connect: (Context) -> SpeechClient = ::SdkSpeechClient) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var client: SpeechClient? = null
    private var ready = false
    private var waiting: String? = null

    fun say(text: String) {
        val c = client
        if (c != null && ready) {
            speak(c, text)
            return
        }
        waiting = text
        if (c != null) return // connecting: the line above replaces the one that waited
        val fresh = connect(app)
        client = fresh
        fresh.initialize(
            onReady = {
                main.post {
                    if (client !== fresh) return@post
                    ready = true
                    waiting?.let { speak(fresh, it) }
                    waiting = null
                }
            },
            onFailure = { reason ->
                main.post {
                    Log.w(TAG, "SPEECH unavailable reason=$reason")
                    if (client === fresh) drop()
                }
            },
        )
    }

    fun release() = drop()

    private fun speak(c: SpeechClient, text: String) {
        Log.i(TAG, "SPEECH say chars=${text.length}")
        c.speak(text) { event -> Log.i(TAG, "SPEECH $event") }
    }

    private fun drop() {
        client?.release()
        client = null
        ready = false
        waiting = null
    }

    /** The calls this speaker makes on the SDK, so tests can stand in for the service Robolectric does not have. */
    interface SpeechClient {
        fun initialize(onReady: () -> Unit, onFailure: (String) -> Unit)

        /** [onEvent]: start, done, stop or error:<type>, from the SDK's event listener. */
        fun speak(text: String, onEvent: (String) -> Unit)
        fun release()
    }

    private class SdkSpeechClient(context: Context) : SpeechClient {
        private val tts = TextToSpeech(context, Mode.ON_DEVICE)
        private var onEvent: (String) -> Unit = {}
        private val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = onEvent("start")
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() = onEvent("done")
            override fun onStop() = onEvent("stop")
            override fun onError(errorType: ErrorType) = onEvent("error:${errorType.name}")
        }

        override fun initialize(onReady: () -> Unit, onFailure: (String) -> Unit) {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    tts.addEventListener(listener)
                    tts.setLocale(Locale.KOREA)
                    onReady()
                }

                override fun onFailure(errorType: ErrorType) = onFailure(errorType.name)
            })
        }

        override fun speak(text: String, onEvent: (String) -> Unit) {
            this.onEvent = onEvent
            tts.speak(text, true)
        }

        override fun release() {
            tts.removeEventListener(listener)
            tts.release()
        }
    }

    private companion object {
        const val TAG = "AutoBriefSpeak"
    }
}
