package com.example.recording.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.recording.service.ServiceController

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            ServiceController.start(context)
        }
    }
}
