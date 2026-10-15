package com.myra.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AssistantService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var active = false
    private var processing = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val restartListening = Runnable { startRecognition() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAssistant()
            return START_NOT_STICKY
        }
        if (!active) {
            active = true
            startAsForegroundService()
            acquireWakeLock()
            initializeTextToSpeech()
            initializeRecognizer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        processing = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        pendingSpeech = null
        scope.cancel()
        AssistantRuntime.listening.value = false
        AssistantRuntime.status.value = "Assistant stopped"
        super.onDestroy()
    }

    private fun initializeRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AssistantRuntime.status.value = "Speech recognition is unavailable"
            AssistantRuntime.lastEvent.value =
                "Enable an Android speech-recognition service, then restart MYRA."
            stopSelf()
            return
        }

        recognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                AssistantRuntime.listening.value = true
                AssistantRuntime.status.value = "Listening — microphone service active"
                updateForegroundNotification()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                AssistantRuntime.listening.value = false
            }

            override fun onError(error: Int) {
                AssistantRuntime.listening.value = false
                if (!active || processing) return
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    AssistantRuntime.status.value = "Microphone permission is required"
                    stopSelf()
                } else {
                    scheduleRecognition(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_500L else 700L)
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                AssistantRuntime.listening.value = false
                val phrase = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (phrase.isNotBlank()) processRecognizedPhrase(phrase)
                else scheduleRecognition(400L)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })

        scheduleRecognition(250L)
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun scheduleRecognition(delayMs: Long) {
        if (!active || processing) return
        handler.removeCallbacks(restartListening)
        handler.postDelayed(restartListening, delayMs)
    }

    private fun startRecognition() {
        if (!active || processing) return
        try {
            recognizer?.startListening(recognitionIntent())
        } catch (_: SecurityException) {
            AssistantRuntime.status.value = "Microphone permission is required"
            stopSelf()
        } catch (_: IllegalStateException) {
            scheduleRecognition(1_000L)
        }
    }

    private fun processRecognizedPhrase(phrase: String) {
        AssistantRuntime.lastEvent.value = "Heard: $phrase"
        when (val command = VoiceCommandParser.parse(phrase)) {
            is VoiceCommand.SetVolume -> speak(AndroidActions.setVolumePercent(this, command.percent))
            is VoiceCommand.ChangeVolume -> speak(AndroidActions.changeVolume(this, command.deltaPercent))
            is VoiceCommand.BlockedSensitiveApp ->
                speak("${command.label} is permanently excluded from MYRA's app commands.")
            is VoiceCommand.OpenApp -> {
                val result = AndroidActions.dispatchFromService(
                    this,
                    AndroidActions.KIND_OPEN_APP,
                    command.label,
                )
                speak(result)
            }
            is VoiceCommand.DialNumber -> {
                val result = AndroidActions.dispatchFromService(
                    this,
                    AndroidActions.KIND_DIAL,
                    command.number,
                )
                speak(result)
            }
            is VoiceCommand.AskAssistant -> askGemini(command.prompt)
        }
    }

    private fun askGemini(prompt: String) {
        processing = true
        recognizer?.cancel()
        AssistantRuntime.status.value = "Thinking"
        scope.launch {
            try {
                val answer = GeminiClient.respond(this@AssistantService, prompt)
                AssistantRuntime.lastEvent.value = "${answer.provider}: ${answer.text}"
                speak(answer.text)
            } catch (error: Exception) {
                val message = error.message ?: "MYRA could not get a reply."
                AssistantRuntime.lastEvent.value = message
                speak(message)
            }
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val engine = textToSpeech
            if (!ttsReady || engine == null) {
                AssistantRuntime.lastEvent.value =
                    "Android text-to-speech is unavailable. Install or enable a speech engine in system settings."
                pendingSpeech = null
                processing = false
                scheduleRecognition(500L)
                return@TextToSpeech
            }
            val languageResult = engine.setLanguage(Locale("en", "IN"))
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.ENGLISH)
                AssistantRuntime.lastEvent.value =
                    "No en-IN voice is installed. Android's default English voice is being used."
            }
            engine.setSpeechRate(0.96f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        processing = false
                        scheduleRecognition(350L)
                    }
                }

                @Deprecated("Deprecated in Android API")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        processing = false
                        scheduleRecognition(350L)
                    }
                }
            })
            pendingSpeech?.let { queued ->
                pendingSpeech = null
                speak(queued)
            }
        }
    }

    private fun speak(text: String) {
        AssistantRuntime.status.value = "Speaking"
        processing = true
        recognizer?.cancel()
        updateForegroundNotification()
        if (!ttsReady || textToSpeech == null) {
            pendingSpeech = text
            return
        }
        val result = textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "myra-${System.currentTimeMillis()}",
        )
        if (result == TextToSpeech.ERROR) {
            processing = false
            AssistantRuntime.status.value = "Speech output failed"
            scheduleRecognition(500L)
        }
    }

    private fun startAsForegroundService() {
        val channelId = "myra_listening"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "MYRA microphone session",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Visible while MYRA listens for voice commands."
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
            )
        }
        val stopIntent = Intent(this, AssistantService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            912,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MYRA is active")
            .setContentText("Listening for commands. Tap Stop to turn off the microphone.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        AssistantRuntime.listening.value = false
        AssistantRuntime.status.value = "Starting microphone service"
    }

    private fun updateForegroundNotification() {
        if (!active) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "myra_listening"
        val stopPendingIntent = PendingIntent.getService(
            this,
            912,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("MYRA is active")
                .setContentText(AssistantRuntime.status.value)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .build(),
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MYRA:Listening").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun stopAssistant() {
        active = false
        pendingSpeech = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var pendingSpeech: String? = null

    companion object {
        const val ACTION_STOP = "com.myra.assistant.STOP"
        const val ACTION_EXECUTE_ACTION = "com.myra.assistant.EXECUTE_ACTION"
        const val NOTIFICATION_ID = 7701
    }
}
