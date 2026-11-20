package com.example.spatialphoto.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.example.spatialphoto.domain.model.CapturePhoto
import com.pico.spatial.ml.readback.readbackContentSuspend
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.PipelineTensorPlaceholder
import com.pico.spatial.ml.securemr.Tensor
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

interface PhotoRepository { suspend fun capture():CapturePhoto }

/** The displayed frame and the saved photograph share the same pixels and calibration. */
data class CameraFrame(val bitmap:Bitmap, val matrix:FloatArray, val timestamp:IntArray,
    val cx:Float, val cy:Float, val receivedAt:Long)
interface CameraPreviewSource {
    suspend fun nextFrame():CameraFrame
    fun selectFrame(frame:CameraFrame)
}


/** Cached single-frame pipeline; never silently substitutes Camera2, depth or test images. */
class VstPhotoRepository(private val context:Context):PhotoRepository,CameraPreviewSource {
 private val captureMutex=kotlinx.coroutines.sync.Mutex()
 @Volatile private var selectedFrame:CameraFrame?=null
 override fun selectFrame(frame:CameraFrame){selectedFrame=frame}
 private data class Source(val owner:SpatialMLInstance,val session:SpatialMLSession,val pipeline:Pipeline,
  val image:GlobalTensor,val intrinsics:GlobalTensor,val timestamp:GlobalTensor,
  val bindings:Map<PipelineTensorPlaceholder,GlobalTensor>)
 private var source:Source?=null
 private suspend fun getSource():Source {
  source?.let{return it}
  val owner=SpatialMLInstance.create(context)
  withTimeout(10000){while(!owner.ready)delay(100)}
  val session=owner.createSession(SpatialMLSession.InitInfo(imageWidth=1920,imageHeight=1440,containerWidth=0,containerHeight=0,containerDepth=0,containerType=SpatialMLSession.ContainerType.DISABLED)) ?: error("相机会话不可用")
  val rgba=session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8A8_U,intArrayOf(1440,1920)))
  val k=session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32,intArrayOf(3,3)))
  val time=session.newGlobalTensor(Tensor.TimeStampInitInfo())
  val pipeline=session.newPipeline()
  val im=pipeline.newPlaceholderLike(rgba);val ki=pipeline.newPlaceholderLike(k);val ti=pipeline.newPlaceholderLike(time)
  pipeline.rectifiedVSTAccess(im,null,ti,ki)
  return Source(owner,session,pipeline,rgba,k,time,mapOf(im to rgba,ki to k,ti to time)).also{source=it}
 }
 override suspend fun nextFrame():CameraFrame=withContext(Dispatchers.IO) {
  captureMutex.lock()
  try {
  for(permission in listOf("android.permission.CAMERA","com.picovr.permission.SPATIAL_DATA"))
   check(context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED){"请授予相机和空间数据权限后重试"}
  val src=getSource()
  withTimeout(15000){
   while(true) {
    src.pipeline.submit(src.bindings,null,null)
    val pixels=src.image.readbackContentSuspend().use{ snapshot->
     val b=snapshot.buffer.duplicate().apply{rewind()};check(b.remaining()==1920*1440*4){"RGBA布局无效"}
     IntArray(1920*1440){0xff000000.toInt() or ((b.get().toInt() and 255) shl 16) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() and 255).also{b.get()}}
    }
    if(pixels.count{it and 0xffffff != 0}<1000){delay(250);continue}
    val matrix=src.intrinsics.readbackContentSuspend().use{ snapshot->val b=snapshot.buffer.duplicate().order(ByteOrder.nativeOrder()).apply{rewind()};check(b.remaining()==36);FloatArray(9){b.float}}
    val timestamp=src.timestamp.readbackContentSuspend().use{ snapshot->val b=snapshot.buffer.duplicate().order(ByteOrder.nativeOrder()).apply{rewind()};check(b.remaining()==16);IntArray(4){b.int}}
    require(matrix.all{it.isFinite()} && matrix[0]>0 && matrix[4]>0 && kotlin.math.abs(matrix[8]-1f)<.01f){"内参无效"}
    val row=kotlin.math.abs(matrix[6])<.001f && kotlin.math.abs(matrix[7])<.001f
    val col=kotlin.math.abs(matrix[2])<.001f && kotlin.math.abs(matrix[5])<.001f
    check(row.xor(col)){"内参存储顺序不明确"}
    val cx=if(row)matrix[2] else matrix[6];val cy=if(row)matrix[5] else matrix[7]
    require(cx in 0f..1920f && cy in 0f..1440f)
    val crop=com.example.spatialphoto.domain.usecase.PhotoFraming.crop(1920,1440,matrix[0],cx,cy)
    val cropped=Bitmap.createBitmap(pixels,crop.top*1920+crop.left,1920,crop.width,crop.height,Bitmap.Config.ARGB_8888)
    val calibrated=matrix.copyOf().apply {this[if(row)2 else 6]=crop.cx;this[if(row)5 else 7]=crop.cy}
    return@withTimeout CameraFrame(cropped,calibrated,timestamp,crop.cx,crop.cy,android.os.SystemClock.elapsedRealtime())
   }
   error("No camera image")
  }
  } finally {captureMutex.unlock()}
 }
 override suspend fun capture():CapturePhoto=withContext(Dispatchers.IO) {
  val frame=selectedFrame?.also{selectedFrame=null} ?: nextFrame()
  save(frame)
 }
 private fun save(frame:CameraFrame):CapturePhoto {
  val captures=File(context.filesDir,"captures")
  check(captures.mkdirs() || captures.isDirectory){"无法创建相册目录"}
  val dir=File(captures,"photo-${System.currentTimeMillis()}")
  check(dir.mkdir()){"无法创建照片目录"}
  val image=File(dir,"photo.png");val partial=File(dir,"photo.png.partial")
  partial.outputStream().use{check(frame.bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
  check(partial.renameTo(image)){"照片保存失败"}
  val matrix=frame.matrix
  val width=frame.bitmap.width;val height=frame.bitmap.height
  File(dir,"camera.json").writeText(JSONObject().put("width",width).put("height",height).put("eye","right").put("intrinsics",JSONArray(matrix.toList())).put("timestampRaw",JSONArray(frame.timestamp.toList())).put("freshCapture",true).put("viewfinderFrame",true).toString())
  android.util.Log.i("SpatialPhoto","PHOTO_SAVED path=${image.absolutePath} width=$width height=$height fx=${matrix[0]} fy=${matrix[4]} viewfinderFrame=true")
  return CapturePhoto(image.absolutePath,width,height,matrix[0],matrix[4],frame.cx,frame.cy)
 }
}
