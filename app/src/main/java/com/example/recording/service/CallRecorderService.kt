package com.example.recording.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.recording.MainActivity
import com.example.recording.R
import com.example.recording.AppPrefs
import com.example.recording.capture.MediaProjectionStore
import com.example.recording.debug.RecordingDebugWriter
import com.example.recording.debug.SessionDebugCollector
import com.example.recording.model.ActiveCallSession
import com.example.recording.model.CallDirection
import com.example.recording.model.CallLifecycleState
import com.example.recording.model.RecordingDebugReport
import com.example.recording.model.UploadPayload
import com.example.recording.recording.RecorderController
import com.example.recording.ui.LiveRecorderStatus
import com.example.recording.upload.UploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class CallRecorderService : Service() {
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var recorderController: RecorderController
    private var callState: CallLifecycleState = CallLifecycleState.IDLE
    private var activeSession: ActiveCallSession? = null
    private val lastTransitionAt = AtomicLong(0L)

    private var sessionDebug: SessionDebugCollector? = null
    private var pendingActiveDetail: String? = null
    private var activeEnteredViaDetail: String? = null
    private lateinit var telephonyListenerModeLabel: String

    /** True after system reports OFFHOOK until next IDLE cleanup. */
    private var telephonySawOffhookSinceIdle: Boolean = false

    private var telephonyRegistered: Boolean = false

    // I — Pre-ring buffer: recording starts at RINGING, promoted at OFFHOOK, discarded on miss
    private var preRingResult: RecorderController.StartResult? = null
    private var preRingStartedAt: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var uiTicker: Runnable? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            MediaProjectionStore.projection = null
            logDbg("MediaProjection revoked by system — dual-channel no longer available")
            pushLiveStatus()
        }
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                telephonySawOffhookSinceIdle = true
            }
            pendingActiveDetail = telephonyDetailForState(state)
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> transitionTo(CallLifecycleState.RINGING)
                TelephonyManager.CALL_STATE_OFFHOOK -> transitionTo(CallLifecycleState.ACTIVE)
                TelephonyManager.CALL_STATE_IDLE -> transitionTo(CallLifecycleState.IDLE)
            }
        }
    }

    @Suppress("DEPRECATION")
    private val legacyPhoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                telephonySawOffhookSinceIdle = true
            }
            pendingActiveDetail = telephonyDetailForState(state)
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> transitionTo(CallLifecycleState.RINGING)
                TelephonyManager.CALL_STATE_OFFHOOK -> transitionTo(CallLifecycleState.ACTIVE)
                TelephonyManager.CALL_STATE_IDLE -> transitionTo(CallLifecycleState.IDLE)
            }
        }
    }

    private fun telephonyDetailForState(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "Telephony: CALL_STATE_RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "Telephony: CALL_STATE_OFFHOOK (call active)"
        TelephonyManager.CALL_STATE_IDLE -> "Telephony: CALL_STATE_IDLE"
        else -> "Telephony: unknown raw state=$state"
    }

    override fun onCreate() {
        super.onCreate()
        recorderController = RecorderController(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyListenerModeLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "TelephonyCallback (API ${Build.VERSION.SDK_INT})"
        } else {
            "PhoneStateListener legacy (API ${Build.VERSION.SDK_INT})"
        }
        createNotificationChannel()
        registerCallbacks()
        startUiTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceController.ACTION_STOP -> {
                Log.i(TAG, "Intent: ACTION_STOP")
                stopCaptureIfAny()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ServiceController.ACTION_MEDIA_PROJECTION_GRANTED -> {
                handleMediaProjectionGranted(intent)
            }
            ServiceController.ACTION_RECORDING_CONFIRMED_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                ensureSessionDebug()
                val detail = intent.getStringExtra(ServiceController.EXTRA_TRIGGER_DETAIL)
                    ?: "Accessibility: confirmed START (no EXTRA)"
                logDbg("Intent: ACCESSIBILITY START — $detail")
                pendingActiveDetail = detail
                if (callState != CallLifecycleState.ACTIVE) {
                    transitionTo(CallLifecycleState.ACTIVE)
                }
            }
            ServiceController.ACTION_RECORDING_CONFIRMED_END -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                val detail = intent.getStringExtra(ServiceController.EXTRA_TRIGGER_DETAIL)
                    ?: "Accessibility: confirmed END (no EXTRA)"
                ensureSessionDebug()
                logDbg("Intent: ACCESSIBILITY END — $detail")
                pendingActiveDetail = detail
                transitionTo(CallLifecycleState.IDLE)
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                logDbg("Foreground started; registering telephony ($telephonyListenerModeLabel)")
                registerCallbacks()
            }
        }
        return START_STICKY
    }

    private fun handleMediaProjectionGranted(intent: Intent) {
        val resultCode = intent.getIntExtra(ServiceController.EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(ServiceController.EXTRA_PROJECTION_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection grant intent missing data")
            return
        }
        // Re-start foreground with mediaProjection type so Android 14+ is satisfied
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // Create the MediaProjection from the result
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = pm.getMediaProjection(resultCode, data)
        // Revoke any previous projection cleanly
        MediaProjectionStore.projection?.stop()
        MediaProjectionStore.projection = proj
        proj.registerCallback(projectionCallback, mainHandler)
        logDbg("MediaProjection ready — dual-channel capture enabled")
        Log.i(TAG, "MediaProjection token stored; dual-channel recording available")
        pushLiveStatus()
    }

    override fun onDestroy() {
        stopUiTicker()
        unregisterCallbacks()
        // Discard any pre-ring buffer that never got promoted
        if (preRingResult != null && activeSession == null) discardPreRingCapture()
        stopCaptureIfAny()
        isRunning.value = false
        pushLiveStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerCallbacks() {
        if (telephonyRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        telephonyRegistered = true
        isRunning.value = true
        Log.i(TAG, "Telephony listeners registered ($telephonyListenerModeLabel)")
        pushLiveStatus()
    }

    private fun unregisterCallbacks() {
        if (!telephonyRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { telephonyManager.unregisterTelephonyCallback(telephonyCallback) }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        telephonyRegistered = false
    }

    private fun startUiTicker() {
        stopUiTicker()
        val tick = object : Runnable {
            override fun run() {
                pushLiveStatus()
                mainHandler.postDelayed(this, 1000L)
            }
        }
        uiTicker = tick
        mainHandler.post(tick)
    }

    private fun stopUiTicker() {
        uiTicker?.let { mainHandler.removeCallbacks(it) }
        uiTicker = null
    }

    private fun readSystemCallStateName(): String {
        return runCatching {
            when (telephonyManager.callState) {
                TelephonyManager.CALL_STATE_IDLE -> "CALL_STATE_IDLE"
                TelephonyManager.CALL_STATE_RINGING -> "CALL_STATE_RINGING"
                TelephonyManager.CALL_STATE_OFFHOOK -> "CALL_STATE_OFFHOOK"
                else -> "other(${telephonyManager.callState})"
            }
        }.getOrElse { "unavailable" }
    }

    private fun pushLiveStatus() {
        val serviceRunning = isRunning.value
        // Show pre-ring buffer as "recording" so the live card appears immediately at RINGING
        val recording = activeSession != null || preRingResult != null
        val elapsed = when {
            activeSession != null ->
                (System.currentTimeMillis() - activeSession!!.startedAt) / 1000L
            preRingResult != null ->
                (System.currentTimeMillis() - preRingStartedAt) / 1000L
            else -> 0L
        }
        val source = activeSession?.sourceUsed ?: preRingResult?.sourceUsed ?: "—"
        val hadAtStart = activeSession?.hadTelephonyOffhookBeforeStart

        val (banner, detail) = buildHealthCopy(
            serviceRunning = serviceRunning,
            recording = recording,
            hadAtStart = hadAtStart,
            appState = callState,
            systemSawOffhook = telephonySawOffhookSinceIdle
        )

        liveStatus.value = LiveRecorderStatus(
            serviceRunning = serviceRunning,
            appCallState = callState.name,
            systemCallState = readSystemCallStateName(),
            telephonySawOffhookThisCall = telephonySawOffhookSinceIdle,
            isRecording = recording,
            recordingElapsedSeconds = elapsed,
            currentAudioSource = source,
            currentCallNumber = activeSession?.number ?: "",
            hadTelephonyOffhookBeforeRecordingStarted = hadAtStart,
            mediaProjectionReady = MediaProjectionStore.projection != null,
            healthBanner = banner,
            detailText = detail
        )
    }

    private fun buildHealthCopy(
        serviceRunning: Boolean,
        recording: Boolean,
        hadAtStart: Boolean?,
        appState: CallLifecycleState,
        systemSawOffhook: Boolean
    ): Pair<String, String> {
        val btActive = isBtScoActive()
        val preRingActive = preRingResult != null && activeSession == null
        val detail = StringBuilder()
        detail.appendLine("App state: $appState")
        detail.appendLine("System TelephonyManager.callState: ${readSystemCallStateName()}")
        detail.appendLine("OFFHOOK seen this call (callback): ${if (systemSawOffhook) "yes" else "no"}")
        detail.appendLine("Bluetooth SCO (headset): ${if (btActive) "ON — headset routing active" else "off"}")
        if (preRingActive) {
            detail.appendLine("Pre-ring buffer: recording since RINGING (awaiting answer)")
        }
        if (recording && !preRingActive) {
            detail.appendLine("Recording: yes")
            detail.appendLine("OFFHOOK before record start: ${hadAtStart ?: "n/a"}")
        } else if (!recording) {
            detail.appendLine("Recording: no")
        }
        detail.appendLine()
        detail.appendLine("How to read this:")
        detail.appendLine("• GREEN path: OFFHOOK = yes before/during capture → telephony aligned.")
        detail.appendLine("• RED path: OFFHOOK = no when capture starts → often silent on many OEMs.")
        detail.appendLine("• BT SCO ON: VOICE_COMMUNICATION routes through headset → better remote capture.")
        detail.appendLine("• Speaker does not fix a blocked call-audio pipe; it only changes room/mic pickup.")

        val banner = when {
            !serviceRunning -> "Service not running — tap Start recording service."
            preRingActive -> "Ringing — pre-ring buffer active${if (btActive) " [BT headset]" else ""}."
            recording && hadAtStart == false ->
                "Risk: recording without Telephony OFFHOOK — may be silent (OEM/policy)."
            recording && hadAtStart == true ->
                "Telephony OFFHOOK confirmed${if (btActive) " + BT SCO headset" else ""} — good signal."
            !recording && appState == CallLifecycleState.IDLE ->
                "Idle — start service, then place call; avoid tapping Start many times."
            else -> "Watching call / recorder — see lines below."
        }
        return banner to detail.toString()
    }

    private fun transitionTo(newState: CallLifecycleState) {
        val now = System.currentTimeMillis()
        if (now - lastTransitionAt.get() < MIN_TRANSITION_GAP_MS && callState == newState) {
            logDbg("ignored(debounce): duplicate transition to $newState")
            return
        }
        lastTransitionAt.set(now)
        if (callState == newState) {
            logDbg("ignored: already in $newState")
            return
        }

        ensureSessionDebug()
        val from = callState
        val detail = pendingActiveDetail
        pendingActiveDetail = null

        if (newState == CallLifecycleState.ACTIVE) {
            activeEnteredViaDetail = detail ?: "unknown"
        }

        logDbg("State $from → $newState${detail?.let { " | $it" } ?: ""}")

        callState = newState
        logCounter("state_${newState.name.lowercase()}_count")
        when (newState) {
            CallLifecycleState.RINGING -> {
                // Mark incoming direction hint
                if (CallSignalStore.lastDirection == CallDirection.UNKNOWN)
                    CallSignalStore.lastDirection = CallDirection.INCOMING
                // I: start recording immediately at ring — no call audio yet but avoids
                // missing the first seconds once the user answers
                startPreRingCapture()
            }
            CallLifecycleState.ACTIVE -> {
                if (preRingResult != null) {
                    // I: promote the pre-ring buffer to the active session
                    promotePreRingToActive()
                } else {
                    // Outgoing call (no RINGING) or accessibility-only trigger
                    startCapture()
                }
            }
            CallLifecycleState.IDLE -> {
                if (preRingResult != null && activeSession == null) {
                    // I: call was missed/rejected — discard the pre-ring file
                    discardPreRingCapture()
                } else {
                    stopCaptureIfAny()
                }
                clearSessionAfterIdle()
            }
        }
        pushLiveStatus()
    }

    private fun startCapture() {
        if (activeSession != null) return
        ensureSessionDebug()
        val number = CallSignalStore.consumeOutgoingNumber().ifBlank { "Unknown" }
        val direction = if (number == "Unknown") CallDirection.INCOMING else CallDirection.OUTGOING
        // H: log BT SCO state at capture start
        val btActive = isBtScoActive()
        logDbg(
            "startCapture: direction=$direction number=${anonymizedNumber(number)} " +
                "(outgoingHint=${CallSignalStore.lastDirection}) btSco=${if (btActive) "ON" else "off"}"
        )

        val hadOffhookBeforeRecorder = telephonySawOffhookSinceIdle
        val (result, attempts) = recorderController.start(direction = direction, number = number)
        if (result == null) {
            logCounter("record_start_fail_count")
            logDbg(
                "Recorder start FAILED — attempts: " +
                    attempts.joinToString { "${it.source}=${it.outcome}${it.error?.let { e -> "($e)" } ?: ""}" }
            )
            Log.w(TAG, "Failed to start recording")
            return
        }

        logDbg("Recorder OK: source=${result.sourceUsed} path=${File(result.filePath).name}")

        activeSession = ActiveCallSession(
            number = number,
            direction = direction,
            startedAt = System.currentTimeMillis(),
            sourceUsed = result.sourceUsed,
            sourceAttempts = result.sourceAttempts,
            filePath = result.filePath,
            activeEntryDetail = activeEnteredViaDetail ?: "unknown",
            telephonyListenerMode = telephonyListenerModeLabel,
            hadTelephonyOffhookBeforeStart = hadOffhookBeforeRecorder,
            debug = sessionDebug!!
        )
    }

    // I: begin recording at RINGING so we don't miss the start of an answered call
    private fun startPreRingCapture() {
        if (preRingResult != null || activeSession != null) return
        ensureSessionDebug()
        val btActive = isBtScoActive()
        logDbg("RINGING: starting pre-ring capture btSco=${if (btActive) "ON" else "off"}")
        val (result, attempts) = recorderController.start(CallDirection.INCOMING, "")
        if (result != null) {
            preRingResult = result
            preRingStartedAt = System.currentTimeMillis()
            logDbg("Pre-ring OK: source=${result.sourceUsed} path=${File(result.filePath).name}")
        } else {
            logDbg(
                "Pre-ring FAILED: " +
                    attempts.joinToString { "${it.source}=${it.outcome}${it.error?.let { e -> "($e)" } ?: ""}" }
            )
        }
    }

    // I: call was answered — attach the pre-ring recording to a proper active session
    private fun promotePreRingToActive() {
        val preResult = preRingResult ?: run { startCapture(); return }
        val number = CallSignalStore.consumeOutgoingNumber().ifBlank { "Unknown" }
        val direction = if (number == "Unknown") CallDirection.INCOMING else CallDirection.OUTGOING
        val btActive = isBtScoActive()
        logDbg(
            "Promoting pre-ring to active: direction=$direction number=${anonymizedNumber(number)} " +
                "btSco=${if (btActive) "ON" else "off"}"
        )
        activeSession = ActiveCallSession(
            number = number,
            direction = direction,
            startedAt = preRingStartedAt,      // keep original start time
            sourceUsed = preResult.sourceUsed,
            sourceAttempts = preResult.sourceAttempts,
            filePath = preResult.filePath,
            activeEntryDetail = "RINGING pre-ring promoted at OFFHOOK${if (btActive) " [BT-SCO]" else ""}",
            telephonyListenerMode = telephonyListenerModeLabel,
            hadTelephonyOffhookBeforeStart = telephonySawOffhookSinceIdle,
            debug = sessionDebug!!
        )
        preRingResult = null
        preRingStartedAt = 0L
    }

    // I: call was missed or rejected — stop recorder and delete the file
    private fun discardPreRingCapture() {
        logDbg("IDLE before ACTIVE: discarding pre-ring file (missed/rejected call)")
        val path = recorderController.stopAndRelease()
        if (path != null) {
            val deleted = File(path).delete()
            logDbg("Pre-ring file ${if (deleted) "deleted" else "delete failed"}: $path")
        }
        preRingResult = null
        preRingStartedAt = 0L
    }

    private fun stopCaptureIfAny() {
        val session = activeSession ?: return
        val stoppedAt = System.currentTimeMillis()
        val savedPath = recorderController.stopAndRelease() ?: session.filePath
        val file = File(savedPath)
        val sizeBytes = if (file.exists()) file.length() else 0L
        session.debug.log("MediaRecorder stopped; file=${file.name} sizeBytes=$sizeBytes")

        val duration = stoppedAt - session.startedAt
        val hints = buildHints(session.sourceUsed, sizeBytes, duration)

        val report = RecordingDebugReport(
            audioFileName = file.name,
            audioFilePath = savedPath,
            deviceManufacturer = Build.MANUFACTURER ?: "",
            deviceModel = Build.MODEL ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            telephonyListenerMode = session.telephonyListenerMode,
            direction = session.direction.name,
            numberLabel = anonymizedNumber(session.number),
            startedAtMillis = session.startedAt,
            stoppedAtMillis = stoppedAt,
            durationMillis = duration,
            audioSourceUsed = session.sourceUsed,
            sourceAttempts = session.sourceAttempts,
            activeCallTriggerSummary = session.activeEntryDetail,
            timeline = session.debug.entries.toList(),
            outputFileSizeBytes = sizeBytes,
            hints = hints,
            hadTelephonyOffhookBeforeStart = session.hadTelephonyOffhookBeforeStart
        )
        RecordingDebugWriter.writeNextToRecording(file, report)

        UploadWorker.enqueue(
            context = this,
            payload = UploadPayload(
                filePath = savedPath,
                number = session.number,
                direction = session.direction.name,
                timestampMillis = session.startedAt,
                durationMillis = duration,
                sourceUsed = session.sourceUsed
            ),
            requireUnmetered = AppPrefs.isWifiOnly(this),
            deleteAfterUpload = AppPrefs.isDeleteAfterUpload(this)
        )
        activeSession = null
        pushLiveStatus()
    }

    private fun clearSessionAfterIdle() {
        sessionDebug = null
        activeEnteredViaDetail = null
        telephonySawOffhookSinceIdle = false
    }

    private fun ensureSessionDebug() {
        if (sessionDebug == null) {
            sessionDebug = SessionDebugCollector()
        }
    }

    private fun logDbg(message: String) {
        ensureSessionDebug()
        sessionDebug!!.log(message)
    }

    private fun buildHints(sourceUsed: String, sizeBytes: Long, durationMs: Long): List<String> {
        val hints = mutableListOf<String>()
        if (sourceUsed == "MIC" || sourceUsed == "VOICE_RECOGNITION") {
            hints.add(
                "Audio source is $sourceUsed — many OEMs route only microphone, not mixed call audio; remote side may be silent."
            )
        }
        if (durationMs > 15_000L && sizeBytes < 50_000L) {
            hints.add("File is very small for call length — likely silence or blocked call audio.")
        }
        hints.add("Compare activeCallTriggerSummary vs timeline: telephony OFFHOOK should appear before record start.")
        hints.add("If only Accessibility fired: telephony callbacks may be blocked; check OEM restrictions / default dialer.")
        return hints
    }

    // H: BT SCO detection — when true, VOICE_COMMUNICATION routes through the headset
    private fun isBtScoActive(): Boolean = try {
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isBluetoothScoOn
    } catch (_: Exception) { false }

    private fun anonymizedNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 4) return "len=${digits.length}"
        return "len=${digits.length} last4=${digits.takeLast(4)}"
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CallRecorderService::class.java).setAction(ServiceController.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop_service), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun logCounter(counterName: String) {
        Log.i(TAG, "metric:$counterName")
    }

    companion object {
        private const val TAG = "CallRecorderService"
        private const val CHANNEL_ID = "call_recorder_channel"
        private const val NOTIFICATION_ID = 9001
        private const val MIN_TRANSITION_GAP_MS = 500L

        val isRunning = MutableStateFlow(false)

        val liveStatus = MutableStateFlow(
            LiveRecorderStatus(
                serviceRunning = false,
                appCallState = CallLifecycleState.IDLE.name,
                systemCallState = "—",
                telephonySawOffhookThisCall = false,
                isRecording = false,
                recordingElapsedSeconds = 0L,
                currentAudioSource = "—",
                hadTelephonyOffhookBeforeRecordingStarted = null,
                healthBanner = "Open this screen during a call to see live alignment.",
                detailText = ""
            )
        )
    }
}
