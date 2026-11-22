package com.example.spatialphoto

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatialphoto.data.repository.GalleryPhotoExporter
import com.example.spatialphoto.platform.LaunchActivity
import com.example.spatialphoto.ui.capture.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.*
import java.io.File
import java.security.MessageDigest

/** Exercises actual renderer resources and exports; never deletes or changes originals. */
@RunWith(AndroidJUnit4::class)
class ReleaseReadinessDeviceTest {
    @Test fun browseRecoverAndExport()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val ctx=inst.targetContext
        ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(50)}
        val vm=checkNotNull(CaptureDiagnostics.viewModel)
        withTimeout(240000){while(vm.state.value.loading || vm.state.value.busy)delay(50)}
        fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val hashes=vm.state.value.photos.associate{it.id to hash(File(it.photo.path))}
        val ready=vm.state.value.photos.filter{it.ready}
        assertTrue("Requires at least two existing real photos",ready.size>=2)
        fun send(event:CaptureEvent)=inst.runOnMainSync{vm.onEvent(event)}
        suspend fun await3D() {
            withTimeout(20000) {
                while(vm.state.value.previewLoading || CaptureDiagnostics.attachedPath!=vm.state.value.scene?.path)delay(20)
            }
            assertFalse(vm.state.value.previewMessage,vm.state.value.previewFailed)
            assertEquals(1,CaptureDiagnostics.previewLiveCount)
            assertTrue(CaptureDiagnostics.fitVerified)
            assertFalse(CaptureDiagnostics.materialVisible)
            assertFalse(CaptureDiagnostics.captureStageActive)
        }
        suspend fun await2D() {
            withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0 || !CaptureDiagnostics.materialVisible)delay(20)}
        }
        val timings=JSONArray()
        try {
            repeat(10){iteration->
                val started=SystemClock.elapsedRealtime()
                send(CaptureEvent.Open(ready[iteration%2].id))
                await3D()
                timings.put(SystemClock.elapsedRealtime()-started)
                send(CaptureEvent.Exit3D)
                await2D()
            }
            // Force only the view state failure; the real source/cache files stay intact.
            send(CaptureEvent.Enter3D);await3D()
            send(CaptureEvent.RenderFailed("验收：模拟加载失败"))
            withTimeout(10000){while(CaptureDiagnostics.previewLiveCount!=0)delay(20)}
            assertTrue(vm.state.value.previewFailed)
            assertFalse(vm.state.value.previewLoading)
            send(CaptureEvent.Enter3D);await3D()
            send(CaptureEvent.Exit3D);await2D()
            send(CaptureEvent.Back)
            val exporter=GalleryPhotoExporter(ctx)
            assertTrue("Choose a system export directory before this check",exporter.hasDestination())
            val first=exporter.export(vm.state.value.photos){_,_->}
            val second=exporter.export(vm.state.value.photos){_,_->}
            assertTrue(second,second.startsWith("已导出 0 个文件"))
            hashes.forEach{(path,expected)->assertEquals(path,expected,hash(File(path)))}
            File(ctx.filesDir,"release-readiness33.json").writeText(JSONObject()
                .put("success",true).put("transitions",20).put("open3DMs",timings)
                .put("renderFailureRecovered",true).put("originalPhotosUnchanged",hashes.size)
                .put("exportFirst",first).put("exportAgain",second).toString(2))
        } finally {
            send(CaptureEvent.Exit3D)
            send(CaptureEvent.Back)
        }
    }
}
