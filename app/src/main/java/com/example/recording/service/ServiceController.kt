package com.example.recording.service

import android.content.Context
import android.content.Intent
import android.os.Build

object ServiceController {
    const val ACTION_START          = "com.example.recording.START"
    const val ACTION_STOP           = "com.example.recording.STOP"
    const val ACTION_RESTART_WATCHER = "com.example.recording.RESTART_WATCHER"
    const val ACTION_RECORDING_CONFIRMED_START = "com.example.recording.RECORDING_CONFIRMED_START"
    const val ACTION_RECORDING_CONFIRMED_END   = "com.example.recording.RECORDING_CONFIRMED_END"
    const val ACTION_MEDIA_PROJECTION_GRANTED  = "com.example.recording.MEDIA_PROJECTION_GRANTED"

    const val EXTRA_TRIGGER_DETAIL        = "trigger_detail"
    const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
    const val EXTRA_PROJECTION_DATA        = "projection_data"

    fun start(context: Context) {
        val intent = Intent(context, CallRecorderService::class.java).setAction(ACTION_START)
        startFgIfNeeded(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, CallRecorderService::class.java).setAction(ACTION_STOP))
    }

    fun notifyAccessibilityRecordingStart(context: Context, detail: String) {
        val intent = Intent(context, CallRecorderService::class.java).apply {
            action = ACTION_RECORDING_CONFIRMED_START
            putExtra(EXTRA_TRIGGER_DETAIL, detail)
            setPackage(context.packageName)
        }
        startFgIfNeeded(context, intent)
    }

    fun notifyAccessibilityRecordingEnd(context: Context, detail: String) {
        val intent = Intent(context, CallRecorderService::class.java).apply {
            action = ACTION_RECORDING_CONFIRMED_END
            putExtra(EXTRA_TRIGGER_DETAIL, detail)
            setPackage(context.packageName)
        }
        context.startService(intent)
    }

    /** Restart the OEM folder watcher with updated brand/folder settings. */
    fun restartWatcher(context: Context) {
        context.startService(
            Intent(context, CallRecorderService::class.java)
                .setAction(ACTION_RESTART_WATCHER)
        )
    }

    /** Called from MainActivity after user grants MediaProjection permission. */
    fun notifyMediaProjectionGranted(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, CallRecorderService::class.java).apply {
            action = ACTION_MEDIA_PROJECTION_GRANTED
            putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, data)
        }
        startFgIfNeeded(context, intent)
    }

    private fun startFgIfNeeded(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
