package io.github.revocx35.telerouter.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.revocx35.telerouter.R
import io.github.revocx35.telerouter.app
import io.github.revocx35.telerouter.wake.WakeWord

/**
 * The system's default assistant (Settings → Apps → Default apps → Digital assistant app).
 *
 * The system keeps this service running while the app is the assistant, which is what makes an always-on wake
 * word affordable (the approach of Home Assistant's companion app): while "Hey Jarvis" is on, it runs as a
 * microphone foreground service and the on-device detector reads the mic; nothing leaves the phone until it
 * fires. The assistant button/gesture and the lock screen open a session, which shows [AssistActivity].
 */
class AssistService : VoiceInteractionService() {
    private var wake: WakeWord? = null
    private var receiver: BroadcastReceiver? = null
    private var ready = false
    private var tornDown = false

    override fun onReady() {
        super.onReady()
        if (tornDown) return                    // the system can deliver onReady while winding the service down
        ready = true
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = apply()
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_APPLY), ContextCompat.RECEIVER_NOT_EXPORTED)
        apply()
    }

    override fun onShutdown() {
        super.onShutdown()
        tornDown = true
        ready = false
        receiver?.let { r ->
            receiver = null
            try { unregisterReceiver(r) } catch (_: IllegalArgumentException) {}
        }
        stopWake()
    }

    override fun onDestroy() {
        tornDown = true
        stopWake()
        super.onDestroy()
    }

    override fun onLaunchVoiceAssistFromKeyguard() = launch()

    /** Start or stop the wake word to match the setting. */
    private fun apply() {
        val a = app
        if (a.store.wakeWord && a.store.server() != null && a.assistant.hasMic) startWake() else stopWake()
    }

    private fun startWake() {
        if (wake?.running == true) return
        val w = WakeWord(this) { onWake() }
        try {
            w.start()
        } catch (e: Exception) {
            Log.e(TAG, "wake word could not start", e)
            return
        }
        wake = w
        app.assistant.wakeWord = w
        foreground()
    }

    private fun stopWake() {
        wake?.stop()
        wake = null
        app.assistant.wakeWord = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun onWake() {
        // the conversation starts recording at once (same mic); the session shows it on screen
        ContextCompat.getMainExecutor(this).execute {
            if (app.assistant.begin(showSheet = false)) launch()
        }
    }

    private fun launch() {
        if (ready) showSession(Bundle(), 0)
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.listening_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.listening_channel_description); setShowBadge(false) })
        val stop = PendingIntent.getBroadcast(this, 0, Intent(ACTION_STOP).setPackage(packageName)
            .setClass(this, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.listening_title))
            .setContentText(getString(R.string.listening_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_listening), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "telerouter.assist"
        private const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 1
        const val ACTION_APPLY = "io.github.revocx35.telerouter.APPLY_WAKE_WORD"
        const val ACTION_STOP = "io.github.revocx35.telerouter.STOP_WAKE_WORD"

        fun isAssistant(c: Context) = isActiveService(c, ComponentName(c, AssistService::class.java))

        /** Tell the running service the wake word setting changed (dropped when it isn't running). */
        fun applySetting(c: Context) = c.sendBroadcast(Intent(ACTION_APPLY).setPackage(c.packageName))
    }
}

/** The notification's "Stop listening": turn the setting off. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.app.store.wakeWord = false
        AssistService.applySetting(c)
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** A session only opens the conversation sheet (and starts a conversation if the wake word didn't). */
class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val a = context.app.assistant
        if (a.current.value?.active != true) a.begin(showSheet = false)
        context.startActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // finish after the framework's doShow() completes, or the window token is gone too early
        android.os.Handler(android.os.Looper.getMainLooper()).post { finish() }
    }
}

/** Required by the voice-interaction metadata; telerouter does the speech recognition. */
class RecognitionStub : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) = listener.error(SpeechRecognizer.ERROR_CLIENT)
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
