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
class PreparedOpenDeviceTest {
 @Test fun firstOpenReusesAllPreparedAssets()=runBlocking {
    val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
    ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(50)}
    val vm=CaptureDiagnostics.viewModel!!
    withTimeout(180000){while(vm.state.value.loading || vm.state.value.busy || vm.state.value.photos.any{it.progress!=null})delay(100)}
    val item=vm.state.value.photos.first{it.ready};val dir=File(item.id).parentFile!!
    fun caches()=dir.listFiles()!!.filter{it.name.startsWith("color-v1-") || it.name.startsWith("window-photo-v8-")}.associate{it.name to (it.length() to it.lastModified())}
    val before=caches();assertTrue(before.keys.any{it.startsWith("window-photo-v8-")});assertTrue(before.keys.any{it.startsWith("color-v1-")})
    val times=JSONArray()
    try {repeat(3){
        i.runOnMainSync{vm.onEvent(CaptureEvent.Open(item.id))}
        val start=SystemClock.elapsedRealtime()
        i.runOnMainSync{vm.onEvent(CaptureEvent.Enter3D)}
        withTimeout(15000){while(CaptureDiagnostics.attachedPath!=item.scene!!.path || vm.state.value.previewLoading)delay(20)}
        times.put(SystemClock.elapsedRealtime()-start)
        assertTrue(CaptureDiagnostics.fitVerified);assertEquals(before,caches());assertFalse(CaptureDiagnostics.captureStageActive)
        i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}
        withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0 || !CaptureDiagnostics.materialVisible)delay(20)}
    }}finally{i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}}
    File(ctx.filesDir,"prepared-open11.json").writeText(JSONObject().put("success",true).put("photo",dir.name).put("openMs",times).put("cachesUnchanged",true).put("preparedBeforeFirstOpen",true).toString(2))
 }
}
