package com.example.spatialphoto

import androidx.compose.runtime.MonotonicFrameClock
import com.example.spatialphoto.ui.capture.awaitCameraSurfaceDetach
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureExitPolicyTest {
 @Test fun stoppedStageFrameClockCannotHoldReturnIndefinitely()=runTest {
    val stopped=object:MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R=awaitCancellation()
    }
    val start=testScheduler.currentTime
    assertFalse(withContext(stopped){awaitCameraSurfaceDetach()})
    assertEquals(250,testScheduler.currentTime-start)
    // Coroutine remains usable: timeout belongs only to detach, not subsequent navigation.
    currentCoroutineContext().ensureActive()
 }
 @Test fun healthyFramesAllowImmediateReturnWithoutFixedDelay()=runTest {
    var frames=0
    val live=object:MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R {yield();return onFrame((++frames)*11_111_111L)}
    }
    assertTrue(withContext(live){awaitCameraSurfaceDetach()})
    assertEquals(2,frames);assertEquals(0,testScheduler.currentTime)
 }
}
