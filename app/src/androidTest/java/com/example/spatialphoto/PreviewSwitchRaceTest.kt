package com.example.spatialphoto

import android.content.Intent
import android.os.SystemClock
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
class PreviewSwitchRaceTest {
 @Test fun coalescedRoundTripCompletesLoading()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
  ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(20)}
  val vm=CaptureDiagnostics.viewModel!!
  withTimeout(180000){while(vm.state.value.loading || vm.state.value.busy)delay(50)}
  val photos=vm.state.value.photos;assertTrue(photos.size>=2 && photos.take(2).all{it.ready})
  val first=photos.first().id
  suspend fun ready(){withTimeout(15000){while(vm.state.value.previewLoading || CaptureDiagnostics.attachedPath!=vm.state.value.scene?.path)delay(20)};assertEquals(1,CaptureDiagnostics.previewLiveCount)}
  val timings=JSONArray()
  try {
   i.runOnMainSync{vm.onEvent(CaptureEvent.Open(first))};ready()
   repeat(20){
    val before=SystemClock.elapsedRealtime()
    // Both requests occur in one main-thread turn: Compose may never observe the middle photo.
    i.runOnMainSync{vm.onEvent(CaptureEvent.Browse(1));vm.onEvent(CaptureEvent.Browse(-1))}
    assertEquals(first,vm.state.value.selectedId);ready();timings.put(SystemClock.elapsedRealtime()-before)
   }
   File(ctx.filesDir,"preview-race13.json").writeText(JSONObject().put("success",true).put("sameFrameRoundTrips",20).put("readyMs",timings).toString(2))
  } finally {
   i.runOnMainSync{vm.onEvent(CaptureEvent.Exit3D);vm.onEvent(CaptureEvent.Back)}
   withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0)delay(20)}
  }
 }
}
