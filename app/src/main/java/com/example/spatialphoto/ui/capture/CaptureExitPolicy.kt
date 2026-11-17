package com.example.spatialphoto.ui.capture

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.withTimeoutOrNull

/** An empty/minimized Stage may stop delivering frames. Detachment must never hold navigation. */
internal suspend fun awaitCameraSurfaceDetach():Boolean = withTimeoutOrNull(250) {
    withFrameNanos { };withFrameNanos { };true
} ?: false
