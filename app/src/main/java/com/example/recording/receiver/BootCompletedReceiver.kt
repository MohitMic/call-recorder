package com.example.recording.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.recording.service.ServiceController
import com.example.recording.upload.PeriodicSweepWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Foreground service handles the live FileObserver path while it's
            // alive. Periodic worker is the safety net for when the OEM kills
            // the service — runs every 15 min independently of the service.
            ServiceController.start(context)
            PeriodicSweepWorker.schedule(context)
        }
    }
}
