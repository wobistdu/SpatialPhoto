package com.example.spatialphoto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatialphoto.data.repository.PhotoColorRefinement
import com.example.spatialphoto.domain.model.CapturePhoto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class PhotoRefinementDeviceTest {
 @Test fun fixedCountGeometryOpacityAndSourceArePreserved() {
    val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    val directory=File(ctx.filesDir,"captures").listFiles()!!.filter{File(it,"scene.ply").isFile}.maxBy{it.name}
    val json=JSONObject(File(directory,"camera.json").readText());val k=json.getJSONArray("intrinsics")
    val photo=CapturePhoto(File(directory,"photo.png").path,json.getInt("width"),json.getInt("height"),k.getDouble(0).toFloat(),k.getDouble(4).toFloat(),k.getDouble(2).toFloat(),k.getDouble(5).toFloat())
    val source=File(directory,"scene.ply");val before=source.readBytes()
    val start=SystemClock.elapsedRealtime();val refined=PhotoColorRefinement.prepare(source,photo);val firstMs=SystemClock.elapsedRealtime()-start
    val after=refined.readBytes();assertEquals(before.size,after.size)
    val offset=before.toString(Charsets.ISO_8859_1).indexOf("end_header\n")+11
    val count=(before.size-offset)/56;val a=ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN);val b=ByteBuffer.wrap(after).order(ByteOrder.LITTLE_ENDIAN)
    var changed=0;var maxDelta=0f
    repeat(count){i->repeat(14){c->val p=offset+i*56+c*4
      if(c in 3..5){val d=kotlin.math.abs(a.getFloat(p)-b.getFloat(p))*.28209479177387814f;maxDelta=maxOf(maxDelta,d);assertTrue(d<=.12001f);if(d>.001f)changed++}
      else assertEquals("point=$i field=$c",a.getInt(p),b.getInt(p))
    }}
    assertTrue(changed>count/10);assertArrayEquals(before,source.readBytes())
    val hash=MessageDigest.getInstance("SHA-256").digest(after)
    val mtime=refined.lastModified();val secondStart=SystemClock.elapsedRealtime();val cached=PhotoColorRefinement.prepare(source,photo);val cacheMs=SystemClock.elapsedRealtime()-secondStart
    assertEquals(refined,cached);assertEquals(mtime,cached.lastModified());assertArrayEquals(hash,MessageDigest.getInstance("SHA-256").digest(cached.readBytes()))
    File(ctx.filesDir,"photo-refinement-test.json").writeText(JSONObject().put("success",true).put("photo",directory.name).put("countBefore",count).put("countAfter",count).put("geometryAndOpacityByteIdentical",true).put("originalUnchanged",true).put("maxColorDelta",maxDelta).put("firstMs",firstMs).put("cacheMs",cacheMs).put("derived",refined.path).toString(2))
 }
}
