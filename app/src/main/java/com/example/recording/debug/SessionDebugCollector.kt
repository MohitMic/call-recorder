package com.example.recording.debug

import com.example.recording.model.DebugTimelineEntry

class SessionDebugCollector {
    val entries = mutableListOf<DebugTimelineEntry>()

    fun log(message: String) {
        entries.add(DebugTimelineEntry(System.currentTimeMillis(), message))
    }
}
