package com.example.recording.capture

import android.media.projection.MediaProjection

/**
 * Process-lifetime singleton that holds an active [MediaProjection] token.
 *
 * The token is obtained in [MainActivity] via [MediaProjectionManager.createScreenCaptureIntent]
 * and stored here so [DualChannelRecorder] can use it from inside the foreground service.
 *
 * Lifecycle:
 *  - Set in MainActivity.onActivityResult (MediaProjection permission granted).
 *  - Cleared after [DualChannelRecorder] calls mediaProjection.stop() on recording end, or when
 *    the user explicitly revokes / the process dies.
 */
object MediaProjectionStore {
    @Volatile
    var projection: MediaProjection? = null
}
