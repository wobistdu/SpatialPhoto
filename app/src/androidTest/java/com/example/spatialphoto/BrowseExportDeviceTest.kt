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

@RunWith(AndroidJUnit4::class)
class BrowseExportDeviceTest {
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    @Test fun prepareOwnedCopies() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val report=File(ctx.filesDir,"browse13-fixture.json");check(!report.exists()){"Previous fixture needs cleanup"}
        val root=File(ctx.filesDir,"captures");val original=root.listFiles()!!.first{File(it,"scene.json").isFile()}
        val hashes=JSONObject();root.listFiles()!!.filter{it.isDirectory}.forEach{dir->listOf("photo.png","scene.ply").forEach{name->File(dir,name).takeIf{it.isFile}?.let{hashes.put("${dir.name}/$name",hash(it))}}}
        val copies=JSONArray();val stamp=System.currentTimeMillis()+3600000
        repeat(2){index->val dest=File(root,"photo-${stamp+index}");check(!dest.exists());check(original.copyRecursively(dest));copies.put(dest.name)}
        report.writeText(JSONObject().put("copies",copies).put("originalHashes",hashes).toString(2))
    }
    @Test fun browseResourcesAndExportDedup()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
        ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(50)}
        val vm=CaptureDiagnostics.viewModel!!
        withTimeout(180000){while(vm.state.value.loading || vm.state.value.busy)delay(50)}
        val fixture=JSONObject(File(ctx.filesDir,"browse13-fixture.json").readText());val copies=fixture.getJSONArray("copies")
        val copyIds=(0 until copies.length()).map{File(ctx.filesDir,"captures/${copies.getString(it)}/photo.png").absolutePath}
        val times=JSONArray()
        suspend fun awaitReady(){withTimeout(20000){while(vm.state.value.previewLoading || CaptureDiagnostics.attachedPath!=vm.state.value.scene?.path)delay(20)};assertEquals(1,CaptureDiagnostics.previewLiveCount);assertTrue(CaptureDiagnostics.fitVerified);assertFalse(CaptureDiagnostics.materialVisible);assertFalse(CaptureDiagnostics.captureStageActive)}
        try {
            assertTrue(vm.state.value.photos.count{it.ready}>=3)
            i.runOnMainSync{vm.onEvent(CaptureEvent.Open(copyIds.last()))};assertTrue(vm.state.value.viewing3D);awaitReady()
            repeat(6){n->
                val direction=if(n%2==0)1 else -1
                val before=vm.state.value.selectedId;val started=SystemClock.elapsedRealtime()
                i.runOnMainSync{vm.onEvent(CaptureEvent.Browse(direction))};assertNotEquals(before,vm.state.value.selectedId);awaitReady();times.put(SystemClock.elapsedRealtime()-started)
            }
            // Cancel mid-load by switching twice; only the final resource may remain attached.
            i.runOnMainSync{vm.onEvent(CaptureEvent.Browse(1));vm.onEvent(CaptureEvent.Browse(1))};awaitReady()
            i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}
            withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0 || !CaptureDiagnostics.materialVisible)delay(20)}
            val before=vm.state.value.selectedId;i.runOnMainSync{vm.onEvent(CaptureEvent.Browse(-1))};assertNotEquals(before,vm.state.value.selectedId);assertFalse(vm.state.value.viewing3D)
            i.runOnMainSync{vm.onEvent(CaptureEvent.Back)};assertNull(vm.state.value.selectedId)
            val export=GalleryPhotoExporter(ctx);assertTrue("First directory grant required",export.hasDestination())
            // Only real user photos are exported. Test copies never leave private app storage.
            val real=vm.state.value.photos.filter{it.id !in copyIds}
            val first=export.export(real){_,_->};val second=export.export(real){_,_->}
            assertTrue(second.startsWith("已导出 0 个文件"))
            File(ctx.filesDir,"browse-export13.json").writeText(JSONObject().put("success",true).put("browseMs",times).put("exportFirst",first).put("exportRepeat",second).put("peakLiveAfterAttach",1).toString(2))
        } finally {
            i.runOnMainSync{vm.onEvent(CaptureEvent.Exit3D);vm.onEvent(CaptureEvent.Back)}
            withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0)delay(20)}
            for(id in copyIds){i.runOnMainSync{vm.onEvent(CaptureEvent.Delete(id))};withTimeout(15000){while(vm.state.value.deletingIds.isNotEmpty())delay(20)}}
            val hashes=fixture.getJSONObject("originalHashes");hashes.keys().forEach{path->assertEquals(hashes.getString(path),hash(File(ctx.filesDir,"captures/$path")))}
            check(File(ctx.filesDir,"browse13-fixture.json").delete())
        }
    }
}
