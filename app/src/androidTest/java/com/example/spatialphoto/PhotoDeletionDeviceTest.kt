package com.example.spatialphoto

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatialphoto.data.repository.FilePhotoLibraryRepository
import com.example.spatialphoto.platform.LaunchActivity
import com.example.spatialphoto.ui.capture.CaptureDiagnostics
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated copy of an actual reconstructed photo. Never deletes historical originals. */
@RunWith(AndroidJUnit4::class)
class PhotoDeletionDeviceTest {
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf=ByteArray(1024*1024)
            while(true){val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun manifest(root:File,except:String?=null):JSONObject {
        val out=JSONObject()
        root.walkTopDown().onEnter{it.name!=except && !it.name.startsWith(".deleted-")}.filter{it.isFile}.forEach{
            out.put(it.relativeTo(root).path,hash(it))
        }
        return out
    }
    @Test fun prepareCopy()=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val captures=File(ctx.filesDir,"captures")
        val source=FilePhotoLibraryRepository(captures).list().firstOrNull{it.ready} ?: error("需要保留的真实重建照片")
        val name="photo-999${System.currentTimeMillis()}"
        val copy=File(captures,name)
        check(!copy.exists() && copy.mkdir())
        File(source.id).parentFile!!.listFiles()!!.filter{it.isFile}.forEach{it.copyTo(File(copy,it.name),overwrite=false)}
        val scene=File(copy,"scene.json")
        scene.writeText(JSONObject(scene.readText()).put("path",File(copy,"scene.ply").absolutePath).put("deletionTestCopy",true).toString())
        assertTrue(FilePhotoLibraryRepository(captures).list().any{it.id==File(copy,"photo.png").absolutePath && it.ready})
        File(ctx.filesDir,"deletion-test-baseline.json").writeText(JSONObject()
            .put("testDirectory",name).put("sourceDirectory",File(source.id).parentFile!!.name)
            .put("captures",manifest(captures,name)).put("models",manifest(File(ctx.filesDir,"models")))
            .put("testBytes",copy.walkTopDown().filter{it.isFile}.sumOf{it.length()}).toString())
    }
    @Test fun verifyDeletedAndColdStart()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
        val baseline=JSONObject(File(ctx.filesDir,"deletion-test-baseline.json").readText())
        val captures=File(ctx.filesDir,"captures");val name=baseline.getString("testDirectory")
        assertFalse("整个测试照片目录必须删除",File(captures,name).exists())
        assertFalse("不允许残留待清理墓碑",captures.listFiles()!!.any{it.name.startsWith(".deleted-$name-")})
        val remaining=FilePhotoLibraryRepository(captures).list()
        assertFalse(remaining.any{File(it.id).parentFile!!.name==name})
        fun compare(expected:JSONObject,actual:JSONObject) {
            assertEquals(expected.keys().asSequence().toSet(),actual.keys().asSequence().toSet())
            expected.keys().forEach{assertEquals("文件未被修改：$it",expected.getString(it),actual.getString(it))}
        }
        compare(baseline.getJSONObject("captures"),manifest(captures))
        compare(baseline.getJSONObject("models"),manifest(File(ctx.filesDir,"models")))
        ctx.startActivity(Intent(ctx,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30000){while(CaptureDiagnostics.viewModel==null || CaptureDiagnostics.viewModel!!.state.value.loading)delay(100)}
        assertFalse(CaptureDiagnostics.viewModel!!.state.value.photos.any{File(it.id).parentFile!!.name==name})
        assertNull(CaptureDiagnostics.viewModel!!.state.value.selectedId)
        assertEquals(0,CaptureDiagnostics.previewLiveCount)
        File(ctx.filesDir,"deletion-test-report.json").writeText(JSONObject()
            .put("success",true).put("testDirectory",name).put("copiedFromRealCapture",true)
            .put("wholeDirectoryRemoved",true).put("cleanupPending",false)
            .put("historicalCapturesHashUnchanged",true).put("modelHashesUnchanged",true)
            .put("coldStartNoRestore",true).put("previewLiveCount",0)
            .put("uiClickVerifiedSeparately",true).put("removedBytes",baseline.getLong("testBytes"))
            .put("stereoAccepted",false).put("headMotionAccepted",false).toString())
    }
}
