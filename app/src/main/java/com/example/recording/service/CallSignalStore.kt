package com.example.recording.service

import com.example.recording.model.CallDirection
import java.util.concurrent.atomic.AtomicReference

object CallSignalStore {
    private val outgoingNumberRef = AtomicReference<String>("")

    @Volatile
    var lastDirection: CallDirection = CallDirection.UNKNOWN

    fun setOutgoingNumber(number: String) {
        outgoingNumberRef.set(number)
        lastDirection = CallDirection.OUTGOING
    }

    fun consumeOutgoingNumber(): String {
        return outgoingNumberRef.getAndSet("")
    }
}
