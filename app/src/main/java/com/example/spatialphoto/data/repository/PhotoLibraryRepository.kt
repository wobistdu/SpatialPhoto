package com.example.spatialphoto.data.repository

import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.BuildConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

interface PhotoLibraryRepository {
    suspend fun markQueued(photo:CapturePhoto) {}
    suspend fun list(): List<PhotoItem>
    suspend fun saveScene(photo: CapturePhoto, scene: GeneratedScene)
    suspend fun saveFailure(photo: CapturePhoto, reason: String)
    suspend fun delete(photo: CapturePhoto):String?
}

/** Reads existing private captures without rewriting/deleting them. Durable queue markers survive process recreation; legacy interrupted jobs remain retryable. */
class FilePhotoLibraryRepository(private val captures: File): PhotoLibraryRepository {
    override suspend fun list(): List<PhotoItem> = withContext(Dispatchers.IO) {
        captures.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".deleted-") && File(it,"photo.png").length()>0 }
            ?.sortedByDescending { it.name }?.mapNotNull { dir ->
                try {
                    val meta=JSONObject(File(dir,"camera.json").readText())
                    val array=meta.getJSONArray("intrinsics")
                    require(array.length()==9)
                    val k=FloatArray(9){array.getDouble(it).toFloat()}
                    val row=abs(k[6])<.001f && abs(k[7])<.001f
                    val col=abs(k[2])<.001f && abs(k[5])<.001f
                    require(row.xor(col) && k.all { it.isFinite() })
                    val w=meta.getInt("width");val h=meta.getInt("height")
                    val cx=if(row)k[2] else k[6];val cy=if(row)k[5] else k[7]
                    require(w>0 && h>0 && k[0]>0 && k[4]>0 && cx in 0f..w.toFloat() && cy in 0f..h.toFloat())
                    val photo=CapturePhoto(File(dir,"photo.png").absolutePath,w,h,k[0],k[4],cx,cy)
                    val json=File(dir,"scene.json");val ply=File(dir,"scene.ply")
                    val scene=if(json.isFile && ply.isFile) {
                        val s=JSONObject(json.readText());val count=s.getInt("count")
                        require(count in 101..300000)
                        val header=ply.inputStream().use { input ->
                            val bytes=ByteArray(4096);val n=input.read(bytes)
                            require(n>0);String(bytes,0,n,Charsets.US_ASCII).substringBefore("end_header\n")+"end_header\n"
                        }
                        require(header.startsWith("ply\nformat binary_little_endian 1.0\n") && header.contains("element vertex $count\n"))
                        require(ply.length()==header.toByteArray(Charsets.US_ASCII).size+count*56L)
                        GeneratedScene(ply.absolutePath,count,s.getLong("elapsedMs"),s.optInt("resolution",512),
                            if(s.optString("source")=="DEPTH_SMALL_CAMERA")GaussianSource.DEPTH_SMALL_CAMERA else GaussianSource.SHARP_NDC)
                    } else null
                    val queued=scene==null && File(dir,"queued").isFile
                    PhotoItem(photo,scene,
                        displayTitle=meta.optString("title").takeIf{it.isNotBlank()}?.take(40),
                        builtInSample=meta.optString("origin")=="builtin-ai",queued=queued,queuedAt=if(queued)File(dir,"queued").readText().toLongOrNull() ?: 0L else 0L,progress=if(queued)"等待重建" else null,error=if(scene==null && !queued) {
                        File(dir,"reconstruction-error.txt").takeIf { it.isFile }?.readText()?.take(300)
                            ?: "重建未完成，点击重试"
                    } else null)
                } catch(failure:Exception) {
                    // Keep invalid directories intact; no fabricated calibration or result.
                    android.util.Log.w("SpatialPhoto","LIBRARY_INVALID ${dir.name}: ${failure.message}")
                    null
                }
            } ?: emptyList()
    }
    override suspend fun markQueued(photo:CapturePhoto)=withContext(Dispatchers.IO) {
        val dir=File(photo.path).parentFile!!
        val pending=File(dir,"queued.partial")
        pending.writeText(System.currentTimeMillis().toString())
        check(pending.renameTo(File(dir,"queued"))){"队列保存失败"}
        Unit
    }
    override suspend fun saveScene(photo:CapturePhoto,scene:GeneratedScene)=withContext(Dispatchers.IO) {
        val dir=File(photo.path).parentFile!!
        val target=File(dir,"scene.json");val partial=File(dir,"scene.json.partial")
        partial.writeText(JSONObject().put("path",scene.path).put("count",scene.count)
            .put("elapsedMs",scene.generationMs).put("freshCapture",true)
            .put("resolution",scene.resolution)
            .put("source",scene.source.name)
            .put("backend",if(scene.source==GaussianSource.DEPTH_SMALL_CAMERA)"ORT_CPU_DEPTH_ANYTHING_V2_SMALL_FP32_RELATIVE"
                else "ORT_CPU_SHARP${scene.resolution}_${if(BuildConfig.WEIGHT_ONLY_MODEL)"W8" else "FP16"}").toString())
        check(partial.renameTo(target)){"场景元数据保存失败"}
        File(dir,"queued").delete()
        File(dir,"reconstruction-error.txt").delete()
        Unit
    }
    override suspend fun delete(photo:CapturePhoto):String?=withContext(Dispatchers.IO) {
        val warning=try { PrivatePhotoDeletion.delete(captures,File(photo.path));null }
            catch(pending:PhotoCleanupPendingException){pending.message}
        android.util.Log.i("SpatialPhoto","PHOTO_DELETED directory=${File(photo.path).parentFile?.name} cleanupPending=${warning!=null}")
        warning
    }
    override suspend fun saveFailure(photo:CapturePhoto,reason:String)=withContext(Dispatchers.IO) {
        File(File(photo.path).parentFile,"reconstruction-error.txt").writeText(reason.take(300))
        File(File(photo.path).parentFile,"queued").delete()
        Unit
    }
}
