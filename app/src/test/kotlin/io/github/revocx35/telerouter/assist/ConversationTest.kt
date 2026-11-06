package io.github.revocx35.telerouter.assist

import io.github.revocx35.telerouter.core.Connection
import io.github.revocx35.telerouter.core.Protocol
import io.github.revocx35.telerouter.core.Protocol.Event
import io.github.revocx35.telerouter.core.Protocol.State
import io.github.revocx35.telerouter.core.TelerouterError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTest {
    private class FakeTransport(val l: Connection.Listener, val failWith: TelerouterError? = null, val pump: () -> Unit,
                                features: Set<String>) : Transport {
        val sent = mutableListOf<Any>()
        var connects = 0
        var closed = false
        private val config = Event.Config(100, "0.16.0", "0000000f", "Phone", "en", features)
        override fun connect() {
            connects++; failWith?.let { throw it }
            l.onEvent(config)                               // telerouter sends its config right after accepting
        }
        override fun talk(): Boolean { sent += "talk"; return true }
        override fun cancel(): Boolean { sent += "cancel"; return true }
        override fun utterance(text: String): Boolean { sent += "say:$text"; return true }
        override fun sendAudio(pcm: ByteArray): Boolean { sent += pcm.size; return true }
        override fun close() { closed = true }
        fun server(s: State) = event(Event.StateChanged(s))
        fun event(e: Event) { l.onEvent(e); pump() }
        fun closedWith(e: TelerouterError?) { l.onClosed(e); pump() }
    }

    private class FakeRecognizer : Recognizer {
        var listener: Recognizer.Listener? = null
        var fed = 0
        var starts = 0
        var cancels = 0
        override fun start(language: String, listener: Recognizer.Listener): Recognizer.Session {
            starts++
            this.listener = listener
            return object : Recognizer.Session {
                override fun feed(pcm: ByteArray) { fed++ }
                override fun cancel() { cancels++ }
            }
        }
    }

    private class FakeVoice : Voice {
        val said = mutableListOf<String>()
        val waiting = mutableListOf<() -> Unit>()
        var stops = 0
        override fun speak(text: String, done: () -> Unit) { said += text; waiting += done }
        override fun stop() { stops++; waiting.clear() }
        override fun release() {}
        fun finish() { val w = waiting.toList(); waiting.clear(); w.forEach { it() } }
    }

    private class FakeMic : AudioIn {
        var sink: ((ByteArray) -> Unit)? = null
        override fun attach(sink: (ByteArray) -> Unit) { this.sink = sink }
        override fun detach() { sink = null }
        fun say(frames: Int) = repeat(frames) { sink?.invoke(ByteArray(640)) }
    }

    private class FakeOut : AudioOut {
        val events = mutableListOf<String>()
        override fun play(pcm: ByteArray) { events += "play ${pcm.size}" }
        override fun flush() { events += "flush" }
        override fun chime(listening: Boolean) { events += if (listening) "chime" else "error chime" }
        override fun release() { events += "release" }
    }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val mic = FakeMic()
    private val out = FakeOut()
    private var transport: FakeTransport? = null
    private var ended = 0

    private val rec = FakeRecognizer()
    private val voice = FakeVoice()

    private fun conversation(fail: TelerouterError? = null, onPhone: Boolean = false, speaks: Boolean = true,
                             features: Set<String> = setOf("utterance")) = Conversation(
        transportFactory = { l -> FakeTransport(l, fail, { scope.runCurrent() }, features).also { transport = it } },
        mic = mic, out = out, scope = scope, io = dispatcher, onEnded = { ended++ },
        recognizer = if (onPhone) rec else null, voice = if (onPhone && speaks) voice else null,
    )

    @Test fun audioSaidWhileConnectingIsSentRightAfterTalk() {
        val c = conversation()
        c.start()
        assertEquals(listOf("chime"), out.events)                 // the phone's own start sound, at once
        assertEquals(Phase.CONNECTING, c.state.value.phase)
        mic.say(25)                                               // 0.5 s spoken before the connection is up
        scope.runCurrent()
        val t = transport!!
        assertEquals("talk", t.sent.first())
        assertEquals(List(25) { 640 }, t.sent.drop(1))            // the pre-roll, after talk, nothing lost
        mic.say(3)
        assertEquals(28, t.sent.count { it == 640 })
        assertEquals(Phase.LISTENING, c.state.value.phase)
    }

    @Test fun micIsOnlySentWhileTelerouterListensAndTheSheetFollowsTheServer() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.LISTENING); mic.say(2)
        t.server(State.THINKING); mic.say(5)                      // not sent: telerouter is working
        assertEquals(2, t.sent.count { it == 640 })
        t.event(Event.Transcript(true, "Turn on the light."))
        t.server(State.SPEAKING); t.l.onAudio(ByteArray(3200))
        t.event(Event.Transcript(false, "Done, the light is on."))
        assertEquals(Phase.SPEAKING, c.state.value.phase)
        assertEquals(listOf(Message(true, "Turn on the light."), Message(false, "Done, the light is on.")), c.state.value.messages)
        assertTrue("play 3200" in out.events)
        t.server(State.IDLE)
        assertEquals(Phase.IDLE, c.state.value.phase)
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertEquals(Phase.ENDED, c.state.value.phase)            // quiet after the answer: hang up
        assertTrue(t.closed); assertEquals(1, ended)
    }

    @Test fun askingAgainSoonUsesTheSameConnection() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.LISTENING); t.server(State.THINKING); t.server(State.SPEAKING); t.server(State.IDLE)
        scope.advanceTimeBy(3000)
        c.start(); scope.runCurrent()
        assertEquals(1, t.connects)
        assertEquals(2, t.sent.count { it == "talk" })
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertEquals(Phase.LISTENING, c.state.value.phase)        // listening again: no linger timeout
    }

    @Test fun heyJarvisAgainWhileTheSheetWaitsAsksAgain() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.LISTENING); t.server(State.THINKING); t.server(State.SPEAKING); t.server(State.IDLE)
        scope.advanceTimeBy(2000)
        c.wake(); scope.runCurrent()
        assertEquals(1, t.connects)                               // the same connection
        assertEquals(2, t.sent.count { it == "talk" })
        assertEquals(Phase.LISTENING, c.state.value.phase)
        c.wake(); scope.runCurrent()                              // already listening: nothing happens
        assertEquals(2, t.sent.count { it == "talk" })
    }

    @Test fun heyJarvisDuringTheAnswerInterruptsIt() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.LISTENING); t.server(State.THINKING); t.server(State.SPEAKING)
        c.wake()
        assertEquals("cancel", t.sent.last())                     // the server stops the answer first
        assertTrue("flush" in out.events)
        t.server(State.IDLE); scope.runCurrent()                  // ...then the phone listens again
        assertEquals(2, t.sent.count { it == "talk" })
        assertEquals(Phase.LISTENING, c.state.value.phase)
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertTrue(c.active)                                      // listening, so no hang-up
    }

    @Test fun aCallKeepsTheMicFlowing() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.CALL); mic.say(4)
        assertEquals(Phase.CALL, c.state.value.phase)
        assertEquals(4, t.sent.count { it == 640 })
    }

    @Test fun unreachableShowsAnErrorAndEnds() {
        val c = conversation(TelerouterError("telerouter is not reachable"))
        c.start(); scope.runCurrent()
        assertEquals(Phase.ERROR, c.state.value.phase)
        assertEquals("telerouter is not reachable", c.state.value.error)
        assertTrue("error chime" in out.events)
        assertFalse(mic.sink != null)
        scope.advanceTimeBy(Conversation.ERROR_LINGER_MS + 1)
        assertEquals(Phase.ENDED, c.state.value.phase)
    }

    @Test fun theServersFlushAtTheStartOfATurnKeepsThePhonesChime() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.event(Event.Flush)
        assertFalse("flush" in out.events)
        t.l.onAudio(ByteArray(3200))
        t.event(Event.Flush)                                  // e.g. the call ended: drop what telerouter sent
        assertTrue("flush" in out.events)
        assertTrue(c.state.value.phase == Phase.LISTENING)
    }

    @Test fun anExpiredSignInPointsToTheApp() {
        val c = conversation(TelerouterError("Sign in to telerouter again: open the telerouter app.", signIn = true))
        c.start(); scope.runCurrent()
        assertEquals(Phase.ERROR, c.state.value.phase)
        assertTrue(c.state.value.needsApp)
        c.start(); scope.runCurrent()                             // asking again clears it until it fails again
        assertTrue(c.state.value.needsApp && transport!!.connects == 1)
    }

    @Test fun stopInterruptsTheAnswer() {
        val c = conversation()
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.SPEAKING)
        c.stop()
        assertTrue("cancel" in t.sent && "flush" in out.events && t.closed)
        assertEquals(Phase.ENDED, c.state.value.phase)
    }

    @Test fun losingTheConnectionMidConversationIsReported() {
        val c = conversation()
        c.start(); scope.runCurrent()
        transport!!.closedWith(TelerouterError("the connection was lost"))
        assertEquals(Phase.ERROR, c.state.value.phase)
        assertTrue(Protocol.talk().isNotEmpty())
    }

    // -- speech on the phone ---------------------------------------------------------------------------------

    @Test fun onThePhoneOnlyTheWordsGoToTelerouterAndThePhoneReadsTheAnswer() {
        val c = conversation(onPhone = true)
        c.start()
        assertEquals(Phase.LISTENING, c.state.value.phase)       // listening at once, not after connecting
        assertEquals(1, rec.starts)
        mic.say(25)
        assertEquals(25, rec.fed)                                 // the recogniser hears from the first moment
        scope.runCurrent()
        val t = transport!!
        rec.listener!!.partial("Turn on")
        assertEquals("Turn on", c.state.value.partial)
        rec.listener!!.result("Turn on the light.")
        scope.runCurrent()
        assertEquals(listOf<Any>("say:Turn on the light."), t.sent)   // no talk, no audio
        assertEquals(null, c.state.value.partial)
        assertEquals(Phase.THINKING, c.state.value.phase)
        t.server(State.THINKING)
        t.event(Event.Transcript(true, "Turn on the light."))    // telerouter's echo: shown once
        t.event(Event.Transcript(false, "**Done**, the light is on.", "Done, the light is on."))
        assertEquals(listOf("Done, the light is on."), voice.said)
        assertEquals(listOf(Message(true, "Turn on the light."), Message(false, "**Done**, the light is on.")), c.state.value.messages)
        t.server(State.SPEAKING); t.server(State.IDLE)
        assertEquals(Phase.SPEAKING, c.state.value.phase)        // the phone still reads
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertTrue(c.active)
        voice.finish(); scope.runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertEquals(Phase.ENDED, c.state.value.phase)
        assertEquals(0, t.sent.count { it == 640 })
    }

    @Test fun wordsHeardBeforeTheConnectionIsUpAreSentOnceItIs() {
        val c = conversation(onPhone = true)
        c.start()
        rec.listener!!.result("Hello.")
        scope.runCurrent()
        assertEquals(listOf<Any>("say:Hello."), transport!!.sent)
        assertEquals(Phase.THINKING, c.state.value.phase)
    }

    @Test fun aFollowUpListensOnceThePhoneHasReadTheAnswer() {
        val c = conversation(onPhone = true)
        c.start(); scope.runCurrent()
        val t = transport!!
        rec.listener!!.result("Turn on the light."); scope.runCurrent()
        t.server(State.THINKING)
        t.event(Event.Transcript(false, "Done.", "Done."))
        t.server(State.SPEAKING); t.server(State.IDLE); t.server(State.LISTENING)   // telerouter's follow-up
        assertEquals(1, rec.starts)                               // not while the phone reads
        voice.finish(); scope.runCurrent()
        assertEquals(2, rec.starts)
        assertEquals(2, out.events.count { it == "chime" })
        assertEquals(Phase.LISTENING, c.state.value.phase)
        rec.listener!!.result("And the fan."); scope.runCurrent()
        assertEquals("say:And the fan.", t.sent.last())
        t.server(State.THINKING); t.event(Event.Transcript(false, "Ok.", "Ok.")); t.server(State.IDLE); t.server(State.LISTENING)
        voice.finish(); scope.runCurrent()
        rec.listener!!.result(""); scope.runCurrent()            // nothing said: telerouter stops waiting
        assertEquals("say:", t.sent.last())
        t.server(State.IDLE)
        assertEquals(Phase.IDLE, c.state.value.phase)
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertEquals(Phase.ENDED, c.state.value.phase)
    }

    @Test fun whenTheRecogniserFailsWhisperGetsTheSameAudio() {
        val c = conversation(onPhone = true)
        c.start()
        mic.say(10); scope.runCurrent()
        val t = transport!!
        rec.listener!!.failed(); scope.runCurrent()
        assertEquals("talk", t.sent.first())
        assertEquals(10, t.sent.count { it == 640 })              // what the recogniser heard, nothing lost
        mic.say(2)
        assertEquals(12, t.sent.count { it == 640 })              // and the rest live
        t.server(State.LISTENING); mic.say(1)
        assertEquals(13, t.sent.count { it == 640 })
        t.server(State.THINKING); t.event(Event.Transcript(false, "Ok.", "Ok.")); t.server(State.IDLE)
        voice.finish(); scope.runCurrent()
        c.start(); scope.runCurrent()                             // the rest of this conversation: Whisper
        assertEquals(1, rec.starts)
        assertEquals(2, t.sent.count { it == "talk" })
    }

    @Test fun anOlderTelerouterGetsTheAudioInstead() {
        val c = conversation(onPhone = true, features = emptySet())
        c.start()
        mic.say(5); scope.runCurrent()
        val t = transport!!
        rec.listener!!.result("Turn on the light."); scope.runCurrent()
        assertFalse(t.sent.any { it.toString().startsWith("say:") })
        assertEquals("talk", t.sent.first())
        assertEquals(5, t.sent.count { it == 640 })
        assertTrue(c.state.value.messages.isEmpty())              // Whisper's transcript will show what it heard
    }

    @Test fun heyJarvisWhileThePhoneReadsInterruptsAndListens() {
        val c = conversation(onPhone = true)
        c.start(); scope.runCurrent()
        val t = transport!!
        rec.listener!!.result("Tell me a story."); scope.runCurrent()
        t.server(State.THINKING); t.event(Event.Transcript(false, "Once upon a time…")); t.server(State.IDLE)
        assertEquals(Phase.SPEAKING, c.state.value.phase)
        c.wake()
        assertEquals(1, voice.stops)
        assertEquals(2, rec.starts)
        assertEquals(Phase.LISTENING, c.state.value.phase)
        rec.listener!!.result("Stop."); scope.runCurrent()
        assertEquals("say:Stop.", t.sent.last())
    }

    @Test fun answersWhileTelerouterIsStillBusyWaitForItsIdle() {
        val c = conversation(onPhone = true)
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.THINKING)                                  // e.g. still on the last question
        rec.listener!!.result("Never mind."); scope.runCurrent()
        assertEquals(listOf<Any>("cancel"), t.sent)
        t.server(State.IDLE)
        assertEquals("say:Never mind.", t.sent.last())
    }

    @Test fun nothingSaidOnThePhoneGoesQuiet() {
        val c = conversation(onPhone = true)
        c.start(); scope.runCurrent()
        rec.listener!!.result(""); scope.runCurrent()
        assertTrue(transport!!.sent.isEmpty())
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertTrue(mic.sink == null)
        scope.advanceTimeBy(Conversation.LINGER_MS + 1)
        assertEquals(Phase.ENDED, c.state.value.phase)
    }

    @Test fun aCallStopsThePhonesRecogniserAndSendsTheMic() {
        val c = conversation(onPhone = true)
        c.start(); scope.runCurrent()
        val t = transport!!
        t.server(State.CALL)
        assertEquals(1, rec.cancels)
        mic.say(3)
        assertEquals(3, t.sent.count { it == 640 })
        assertEquals(Phase.CALL, c.state.value.phase)
    }

    @Test fun textOnlyAnswersAreNotRead() {
        val c = conversation(onPhone = true, speaks = false)
        c.start(); scope.runCurrent()
        val t = transport!!
        rec.listener!!.result("Hi."); scope.runCurrent()
        t.server(State.THINKING); t.event(Event.Transcript(false, "Hello.")); t.server(State.SPEAKING); t.server(State.IDLE)
        assertTrue(voice.said.isEmpty())
        assertEquals(Phase.IDLE, c.state.value.phase)
    }

    @Test fun theRecogniserUsesAnInstalledVariantOfTelerouterLanguage() {
        val us = java.util.Locale.US
        assertEquals("en-US", pickLanguagePack("en", listOf("de-DE", "en-US"), us))
        assertEquals("en-GB", pickLanguagePack("en", listOf("en-GB"), us))
        assertEquals("en-US", pickLanguagePack("", listOf("en-US"), us))            // auto: the phone's language
        assertEquals("tr-TR", pickLanguagePack("tr", listOf("en-US", "tr-TR"), us))
        assertEquals(null, pickLanguagePack("tr", listOf("en-US"), us))
    }
}
