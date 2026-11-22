package com.example.spatialphoto

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatialphoto.platform.LaunchActivity
import com.example.spatialphoto.ui.capture.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class FullspaceCaptureTest {
 @Test fun previewFrameIsSavedAndReconstructionReturnsToWindow()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
  ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(100)}
  val vm=CaptureDiagnostics.viewModel!!
  withTimeout(10000){while(vm.state.value.loading)delay(100)}
  val before=vm.state.value.photos.map{it.id}.toSet()
  i.runOnMainSync{vm.onEvent(CaptureEvent.OpenCamera)}
  try {
   withTimeout(25000){while(!CaptureDiagnostics.captureStageActive || vm.state.value.cameraFrame==null)delay(100)}
   delay(InstrumentationRegistry.getArguments().getString("viewfinderHoldMs","10000").toLong())
   val viewport=CaptureDiagnostics.viewfinderSize
   assertTrue(viewport.width in 1..2560 && viewport.height in 1..1440)
   assertEquals(viewport.width*3,viewport.height*4)
   lateinit var chosen:com.example.spatialphoto.data.repository.CameraFrame
   i.runOnMainSync{chosen=vm.state.value.cameraFrame!!;vm.onEvent(CaptureEvent.Shutter);vm.onEvent(CaptureEvent.Shutter)}
   withTimeout(20000){while(vm.state.value.photos.none{it.id !in before})delay(100)}
   val item=vm.state.value.photos.first{it.id !in before}
   val saved=BitmapFactory.decodeFile(item.id)
   assertEquals(4*saved.height,3*saved.width)
   val horizontalFov=2*kotlin.math.atan(saved.width/(2.0*item.photo.fx))*180/kotlin.math.PI
   assertTrue("Expected phone landscape FOV, got $horizontalFov",horizontalFov in 59.8..60.0)
   assertNotNull(saved);assertTrue("Saved pixels must equal the shutter's displayed frame",chosen.bitmap.sameAs(saved));saved.recycle()
   withTimeout(10000){while(CaptureDiagnostics.captureStageActive)delay(100)}
   assertFalse(vm.state.value.cameraRequested);assertNull(vm.state.value.cameraFrame)
   withTimeout(180000){while(vm.state.value.busy)delay(500)}
   assertEquals(1,vm.state.value.photos.count{it.id !in before})
   val ready=vm.state.value.photos.first{it.id==item.id};assertTrue(ready.ready)
   assertNull(vm.state.value.selectedId)
   File(ctx.filesDir,"fullspace-capture-test.json").writeText(JSONObject()
    .put("fullspaceEntered",true).put("viewfinderPixelsEqualSavedPhoto",true)
    .put("returnedToGallery",true).put("scenePath",ready.scene!!.path)
    .put("viewportWidth",viewport.width).put("viewportHeight",viewport.height)
    .put("horizontalFov",horizontalFov).put("width",item.photo.width).put("height",item.photo.height)
    .put("count",ready.scene!!.count).put("generationMs",ready.scene!!.generationMs)
    .put("stereoAccepted",false).put("headMotionAccepted",false).toString())
  } finally {i.runOnMainSync{vm.onEvent(CaptureEvent.CloseCamera)}}
 }
}
