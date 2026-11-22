package com.example.spatialphoto
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
@RunWith(AndroidJUnit4::class)
class GalleryDeleteControlTest {
 @Test fun fixtureForActualPublishedDeleteControl() {
    val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    val report=File(ctx.filesDir,"delete11-fixture.json");val root=File(ctx.filesDir,"captures")
    fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    if(InstrumentationRegistry.getArguments().getString("mode","prepare")=="prepare") {
        val originals=JSONObject()
        val dirs=root.listFiles()!!.filter{it.isDirectory && File(it,"photo.png").isFile() && File(it,"scene.json").isFile()}
        for(dir in dirs)for(name in listOf("photo.png","scene.ply"))originals.put("${dir.name}/$name",hash(File(dir,name)))
        val stamp=System.currentTimeMillis()+3600000
        val fixture=File(root,"photo-$stamp");assertFalse(fixture.exists())
        assertTrue(dirs.maxBy{it.name}.copyRecursively(fixture))
        report.writeText(JSONObject().put("fixture",fixture.name).put("deleteLabel","删除照片 "+SimpleDateFormat("M月d日 · HH:mm",Locale.CHINA).format(Date(stamp))).put("originalHashes",originals).put("verified",false).toString(2))
    } else {
        val data=JSONObject(report.readText());assertFalse("Published delete must remove the whole fixture",File(root,data.getString("fixture")).exists())
        val originals=data.getJSONObject("originalHashes")
        originals.keys().forEach{path->assertEquals("Original changed: $path",originals.getString(path),hash(File(root,path)))}
        assertFalse(root.listFiles()!!.any{it.name.startsWith(".deleted-")})
        report.writeText(data.put("verified",true).put("originalsUnchanged",true).toString(2))
    }
 }
}
