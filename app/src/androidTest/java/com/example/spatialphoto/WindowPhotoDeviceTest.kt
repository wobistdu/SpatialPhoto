package com.example.spatialphoto

import android.content.Intent
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
import java.security.MessageDigest

/** Exercises real native resources, including fast cancellation; screenshots verify visual output separately. */
@RunWith(AndroidJUnit4::class)
class WindowPhotoDeviceTest {
    @Test fun repeatedWindowOpenAndCancelPreservesSourceAndDisposesResources()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
        ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30000){while(CaptureDiagnostics.viewModel==null)delay(100)}
        val vm=CaptureDiagnostics.viewModel!!
        withTimeout(10000){while(vm.state.value.loading)delay(100)}
        val item=if(InstrumentationRegistry.getArguments().getString("quality","standard")=="hd")vm.state.value.photos.first{it.ready && it.scene!!.resolution==768} else vm.state.value.photos.last{it.ready}
        val source=File(item.scene!!.path)
        fun hash()=MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val original=hash()
        try {
            repeat(3){
                i.runOnMainSync{vm.onEvent(CaptureEvent.Open(item.id));assertFalse(vm.state.value.viewing3D);vm.onEvent(CaptureEvent.Enter3D)}
                delay(25)
                i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}
                withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0)delay(50)}
                delay(100)
            }
            repeat(2){
                i.runOnMainSync{vm.onEvent(CaptureEvent.Open(item.id));assertFalse(vm.state.value.viewing3D);vm.onEvent(CaptureEvent.Enter3D)}
                withTimeout(30000){while(CaptureDiagnostics.attachedPath!=source.path)delay(100)}
                assertEquals(1,CaptureDiagnostics.previewLiveCount)
                assertTrue(CaptureDiagnostics.fitVerified)
                assertFalse(CaptureDiagnostics.materialVisible)
                assertTrue(CaptureDiagnostics.windowSize.width>840)
                assertFalse(CaptureDiagnostics.captureStageActive)
                assertArrayEquals(original,hash())
                delay(InstrumentationRegistry.getArguments().getString("holdMs","1000").toLong())
                i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}
                withTimeout(15000){while(CaptureDiagnostics.previewLiveCount!=0)delay(50)}
                assertNull(CaptureDiagnostics.attachedPath)
                withTimeout(10000){while(!CaptureDiagnostics.materialVisible)delay(50)}
                assertEquals(item.id,vm.state.value.selectedId)
            }
            assertFalse(source.parentFile!!.listFiles()!!.any{it.name.contains(".partial-")})
            File(ctx.filesDir,"window-photo-test.json").writeText(JSONObject()
                .put("success",true).put("quickCancelCycles",3).put("loadedCycles",2)
                .put("sourceUnchanged",true).put("boundedWindow",true).put("stageUsed",false)
                .put("nativeOwnersAfterBack",CaptureDiagnostics.previewLiveCount).toString())
        } finally {i.runOnMainSync{vm.onEvent(CaptureEvent.Back)}}
    }
}
