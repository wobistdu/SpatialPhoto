package com.example.spatialphoto

import android.content.Intent
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class ConcurrentCameraTest {
 @Test fun liveCameraRemainsResponsiveAcrossInferenceAndPostprocessing()=runBlocking<Unit> {
  val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
  val handler=Handler(Looper.getMainLooper());val beat=AtomicLong(SystemClock.elapsedRealtime())
  val maxLag=AtomicLong();val stalls=JSONArray();val cycles=JSONArray()
  val report=JSONObject().put("cycles",cycles).put("stalls",stalls)
  val pulse=object:Runnable{override fun run(){beat.set(SystemClock.elapsedRealtime());handler.postDelayed(this,50)}}
  handler.post(pulse)
  val monitor=launch(Dispatchers.Default){while(isActive){
   val lag=SystemClock.elapsedRealtime()-beat.get();maxLag.updateAndGet{maxOf(it,lag)}
   if(lag>1500 && stalls.length()<15)stalls.put(JSONObject().put("lagMs",lag).put("mainStack",Looper.getMainLooper().thread.stackTrace.joinToString("\n")))
   delay(100)
  }}
  suspend fun event(e:CaptureEvent){val done=CompletableDeferred<Unit>();handler.post{CaptureDiagnostics.viewModel!!.onEvent(e);done.complete(Unit)};withTimeout(5000){done.await()}}
  ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  try {
   withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(50)}
   val vm=CaptureDiagnostics.viewModel!!
   withTimeout(120000){while(vm.state.value.loading || vm.state.value.busy)delay(100)}
   suspend fun camera(label:String,save:Boolean,hold:Long=2000) {
    val cycle=JSONObject().put("phase",label);cycles.put(cycle)
    val started=SystemClock.elapsedRealtime();event(CaptureEvent.OpenCamera)
    withTimeout(8000){while(!CaptureDiagnostics.captureStageActive || vm.state.value.cameraFrame==null)delay(20)}
    cycle.put("openMs",SystemClock.elapsedRealtime()-started)
    val until=SystemClock.elapsedRealtime()+hold;var maxAge=0L;var frames=0;var last=0L
    while(SystemClock.elapsedRealtime()<until){
     val f=vm.state.value.cameraFrame
     if(f!=null){maxAge=maxOf(maxAge,SystemClock.elapsedRealtime()-f.receivedAt);if(f.receivedAt!=last){frames++;last=f.receivedAt}}
     delay(50)
    }
    cycle.put("maxFrameAgeMs",maxAge).put("frames",frames)
    assertTrue("Preview froze: $cycle",maxAge<2000);assertTrue("No fresh frames: $cycle",frames>=2)
    cycle.put("activeAtExit",vm.state.value.activeId).put("stageAtExit",vm.state.value.photos.firstOrNull{it.id==vm.state.value.activeId}?.progress)
    val leaving=SystemClock.elapsedRealtime();event(if(save)CaptureEvent.Shutter else CaptureEvent.CloseCamera)
    withTimeout(10000){while(CaptureDiagnostics.captureStageActive || vm.state.value.savingPhoto || vm.state.value.cameraClosing)delay(20)}
    cycle.put("exitMs",SystemClock.elapsedRealtime()-leaving)
    delay(100)
   }
   camera("idle",true)
   withTimeout(15000){while(vm.state.value.photos.none{it.progress?.contains("正在端上重建")==true})delay(100)}
   delay(5000)
   camera("inference-save",true,6000)
   camera("inference-cancel",false,3000)
   repeat(3){camera("rapid-reenter-$it",false,1800)}
   withTimeout(90000){while(vm.state.value.photos.none{it.progress?.contains("准备 3D")==true})delay(50)}
   val saveDuringPost=InstrumentationRegistry.getArguments().getString("saveDuringPost","false")=="true"
   camera(if(saveDuringPost)"postprocessing-save" else "postprocessing-cancel",saveDuringPost,if(saveDuringPost)2000 else 6000)
   withTimeout(180000){while(vm.state.value.busy)delay(100)}
   assertTrue("UI stalled ${maxLag.get()}ms",maxLag.get()<2000)
   report.put("success",true)
  } finally {
   monitor.cancelAndJoin();handler.removeCallbacks(pulse)
   report.put("maxMainHeartbeatGapMs",maxLag.get())
   File(ctx.filesDir,"concurrent-camera12.json").writeText(report.toString(2))
   handler.post{CaptureDiagnostics.viewModel?.onEvent(CaptureEvent.CloseCamera)}
  }
 }
}
