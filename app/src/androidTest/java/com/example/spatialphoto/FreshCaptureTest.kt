package com.example.spatialphoto

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatialphoto.platform.LaunchActivity
import com.example.spatialphoto.ui.capture.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.*
import java.io.File
import org.json.JSONObject

/** Actual product events/camera/model. Does not claim wearer stereo visual acceptance. */
@RunWith(AndroidJUnit4::class)
class FreshCaptureTest {
    @Test fun productPhotoToGaussianGallery()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
        ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(200)}
        val vm=CaptureDiagnostics.viewModel!!
        withTimeout(10000){while(vm.state.value.loading)delay(100)}
        val previous=vm.state.value.photos.map{it.id}.toSet()
        i.runOnMainSync{vm.onEvent(CaptureEvent.Capture);vm.onEvent(CaptureEvent.Capture)}
        withTimeout(20000){while(vm.state.value.photos.none{it.id !in previous})delay(100)}
        val photo=vm.state.value.photos.first{it.id !in previous}
        assertTrue(File(photo.id).length()>0)
        assertTrue(vm.state.value.busy)
        assertNotNull(photo.progress)
        assertNull(CaptureDiagnostics.attachedPath)
        withTimeout(180000){while(vm.state.value.busy)delay(500)}
        val ready=vm.state.value.photos.first{it.id==photo.id}
        assertTrue(ready.ready)
        assertNull(vm.state.value.selectedId)
        assertNull(CaptureDiagnostics.attachedPath)
        val scene=ready.scene!!
        assertTrue(File(scene.path).isFile && scene.count>100000)
        i.runOnMainSync{vm.onEvent(CaptureEvent.Open(photo.id))}
        withTimeout(20000){while(CaptureDiagnostics.attachedPath!=scene.path)delay(200)}
        assertTrue(CaptureDiagnostics.fitVerified)
        assertEquals(1,CaptureDiagnostics.previewLiveCount)
        delay(InstrumentationRegistry.getArguments().getString("previewHoldMs","0").toLong())
        i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}
        withTimeout(10000){while(CaptureDiagnostics.previewLiveCount!=0)delay(100)}
        assertNull(CaptureDiagnostics.attachedPath)
        assertTrue(vm.state.value.photos.first{it.id==photo.id}.ready)
        File(ctx.filesDir,"gallery-capture-test.json").writeText(JSONObject().put("success",true).put("freshCapture",true).put("photoVisibleDuringInference",true).put("completionStayedInGallery",true).put("scenePath",scene.path).put("count",scene.count).put("generationMs",scene.generationMs).put("windowFitVerified",true).put("renderer","native-stereo-window").put("stereoAccepted",false).put("headMotionAccepted",false).put("returnDisposed",true).put("visualAccepted",false).toString())
    }
}
