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
import org.json.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueuedCaptureDeviceTest {
 @Test fun captureAgainWhileFirstReconstructs()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
  ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(100)}
  val vm=CaptureDiagnostics.viewModel!!
  withTimeout(10000){while(vm.state.value.loading)delay(100)}
  val original=vm.state.value.photos.map{it.id}.toSet()
  val ids=mutableListOf<String>();val timings=JSONArray()
  try {
   repeat(2){index->
    val opened=android.os.SystemClock.elapsedRealtime()
    i.runOnMainSync{vm.onEvent(CaptureEvent.OpenCamera)}
    withTimeout(30000){while(!CaptureDiagnostics.captureStageActive || vm.state.value.cameraFrame==null)delay(100)}
    val frame=vm.state.value.cameraFrame!!
    withTimeout(10000){while(vm.state.value.cameraFrame?.timestamp==frame.timestamp)delay(100)}
    lateinit var chosen:com.example.spatialphoto.data.repository.CameraFrame
    i.runOnMainSync{chosen=vm.state.value.cameraFrame!!;vm.onEvent(CaptureEvent.Shutter);vm.onEvent(CaptureEvent.Shutter)}
    withTimeout(20000){while(vm.state.value.photos.none{it.id !in original && it.id !in ids})delay(100)}
    val item=vm.state.value.photos.first{it.id !in original && it.id !in ids};ids+=item.id
    val saved=BitmapFactory.decodeFile(item.id);assertTrue(chosen.bitmap.sameAs(saved));saved.recycle()
    withTimeout(15000){while(CaptureDiagnostics.captureStageActive || vm.state.value.savingPhoto)delay(100)}
    if(index==0)withTimeout(10000){while(vm.state.value.activeId!=ids[0])delay(100)}
    else {assertEquals(ids[0],vm.state.value.activeId);assertTrue(vm.state.value.photos.first{it.id==ids[1]}.queued)}
    timings.put(JSONObject().put("capture",index).put("previewAndSaveMs",android.os.SystemClock.elapsedRealtime()-opened))
   }
   i.runOnMainSync{vm.onEvent(CaptureEvent.Open(ids[1]))}
   assertEquals(ids[1],vm.state.value.selectedId);assertFalse(vm.state.value.viewing3D)
   val order=mutableListOf<String>();var lastPercent=0;var lastId:String?=null
   withTimeout(360000){while(vm.state.value.busy) {
    val s=vm.state.value;val id=s.activeId
    if(id!=null && id!=lastId){order+=id;lastId=id;lastPercent=0}
    val percent=s.photos.firstOrNull{it.id==id}?.percent ?: 0
    assertTrue(percent>=lastPercent);lastPercent=percent
    delay(250)
   }}
   assertEquals(2,vm.state.value.photos.count{it.id !in original})
   assertTrue(ids.all{id->vm.state.value.photos.first{it.id==id}.ready})
   assertEquals(ids[1],vm.state.value.selectedId)
   File(ctx.filesDir,"queue09-test.json").writeText(JSONObject().put("success",true).put("savedFramesMatch",true)
    .put("secondCaptureWhileFirstActive",true).put("secondQueued",true).put("viewing2DWhileQueued",true)
    .put("percentMonotonic",true).put("processingOrder",JSONArray(order)).put("captureTiming",timings)
    .put("photos",JSONArray(ids)).put("generationMs",JSONArray(ids.map{id->vm.state.value.photos.first{it.id==id}.scene!!.generationMs})).toString(2))
  } finally {i.runOnMainSync{vm.onEvent(CaptureEvent.CloseCamera);vm.onEvent(CaptureEvent.Back)}}
 }
}
