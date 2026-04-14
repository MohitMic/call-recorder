package com.example.recording.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.recording.service.CallSignalStore
import com.example.recording.service.ServiceController

class CallRecorderAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (!isCallUi(packageName)) return

        val className = event?.className?.toString().orEmpty()
        when {
            className.contains("InCall", ignoreCase = true) ||
                className.contains("Telecom", ignoreCase = true) -> {
                ServiceController.notifyAccessibilityRecordingStart(
                    this,
                    "Accessibility: window class suggests in-call UI ($className pkg=$packageName)"
                )
            }
            className.contains("CallEnded", ignoreCase = true) -> {
                ServiceController.notifyAccessibilityRecordingEnd(
                    this,
                    "Accessibility: window class suggests call ended ($className pkg=$packageName)"
                )
                CallSignalStore.lastDirection = com.example.recording.model.CallDirection.UNKNOWN
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun isCallUi(packageName: String): Boolean {
        return packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("telecom", ignoreCase = true) ||
            packageName.contains("incallui", ignoreCase = true)
    }
}
