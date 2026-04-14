package com.example.recording.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.recording.service.CallSignalStore

class OutgoingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER).orEmpty()
        CallSignalStore.setOutgoingNumber(number)
        Log.i(TAG, "Outgoing number captured")
    }

    companion object {
        private const val TAG = "OutgoingCallReceiver"
    }
}
