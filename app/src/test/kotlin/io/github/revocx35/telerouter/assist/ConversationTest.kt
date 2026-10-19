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
    private class FakeTransport(val l: Connection.Listener, val failWith: TelerouterError? = null) : Transport {
        val sent = mutableListOf<Any>()
        var connects = 0
        var closed = false
        override val viaInternet = false
        override fun connect() { connects++; failWith?.let { throw it } }
        override fun talk(): Boolean { sent += "talk"; return true }
        override fun cancel(): Boolean { sent += "cancel"; return true }
        override fun sendAudio(pcm: ByteArray): Boolean { sent += pcm.size; return true }
        override fun close() { closed = true }
        fun server(s: State) = l.onEvent(Event.StateChanged(s))
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

    private fun conversation(fail: TelerouterError? = null) = Conversation(
        transportFactory = { l -> FakeTransport(l, fail).also { transport = it } },
        mic = mic, out = out, scope = scope, io = dispatcher, onEnded = { ended++ },
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
        t.l.onEvent(Event.Transcript(true, "Turn on the light."))
        t.server(State.SPEAKING); t.l.onAudio(ByteArray(3200))
        t.l.onEvent(Event.Transcript(false, "Done, the light is on."))
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
        transport!!.l.onClosed(TelerouterError("the connection was lost"))
        assertEquals(Phase.ERROR, c.state.value.phase)
        assertTrue(Protocol.talk().isNotEmpty())
    }
}
