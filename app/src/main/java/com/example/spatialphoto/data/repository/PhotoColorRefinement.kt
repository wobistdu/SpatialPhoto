package com.example.spatialphoto.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.example.spatialphoto.domain.model.CapturePhoto
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Apply the display/export point budget, then fit reference-photo colors only.
 * The optimizer preserves the budgeted rows' geometry, opacity and count.
 * Serialized and cached so rapid open/back cannot launch simultaneous native optimizers.
 */
object PhotoColorRefinement {
    init { System.loadLibrary("photo_refine") }
    private external fun fit(rows:FloatArray,pixels:IntArray,width:Int,height:Int,intrinsics:FloatArray):FloatArray

    private val preparationLock=Any()

    fun prepare(source:File,photo:CapturePhoto,checkCancelled:()->Unit={}):File {
        checkCancelled()
        val started=SystemClock.elapsedRealtime()
        require(source.length() in 1..(300000L*56+4096))
        val budgeted=com.example.spatialphoto.domain.usecase.GaussianBudget.prepare(source,photo,checkCancelled)
        val bytes=budgeted.readBytes()
        val photoFile=File(photo.path)
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        photoFile.inputStream().use { stream-> val buffer=ByteArray(65536);while(true){checkCancelled();val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        digest.update("color-v1:960:12:.12:.02:${photo.width}:${photo.height}:${photo.fx}:${photo.fy}:${photo.cx}:${photo.cy}".toByteArray())
        val key=digest.digest().take(12).joinToString(""){"%02x".format(it.toInt() and 255)}
        val output=File(source.parentFile,"color-v1-$key.ply")
        if(output.isFile && output.length()==bytes.size.toLong())return output
        // A prepared photo must not wait behind another photo's native color fitting.
        return synchronized(preparationLock) {
        checkCancelled()
        if(output.isFile && output.length()==bytes.size.toLong())return@synchronized output
        val marker="end_header\n".toByteArray()
        val start=(0 until minOf(4096,bytes.size-marker.size+1)).firstOrNull{i->marker.indices.all{bytes[i+it]==marker[it]}}
        require(start!=null){"Invalid Gaussian header"}
        val offset=start+marker.size
        val header=bytes.copyOfRange(0,offset).toString(Charsets.US_ASCII)
        require(header.contains("format binary_little_endian 1.0"))
        val fields=listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")
        require(header.lineSequence().filter{it.startsWith("property ")}.toList()==fields.map{"property float $it"})
        val count=header.lineSequence().first{it.startsWith("element vertex ")}.substringAfterLast(' ').toInt()
        require(count in 101..300000 && bytes.size-offset==count*56)
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.position(offset)
        val rows=FloatArray(count*14);data.asFloatBuffer().get(rows)
        require(photo.width>0 && photo.height>0)
        val ratio=minOf(1f,960f/maxOf(photo.width,photo.height))
        val width=(photo.width*ratio).toInt().coerceAtLeast(1);val height=(photo.height*ratio).toInt().coerceAtLeast(1)
        val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=if(ratio<=.5f)2 else 1 }
        val decoded=requireNotNull(BitmapFactory.decodeFile(photo.path,options)){"原始照片读取失败"}
        val resized=try {Bitmap.createScaledBitmap(decoded,width,height,true)}catch(t:Throwable){decoded.recycle();throw t}
        val pixels=IntArray(width*height)
        try {resized.getPixels(pixels,0,width,0,0,width,height)}finally {resized.recycle();if(decoded!==resized)decoded.recycle()}
        checkCancelled()
        val scaleX=width.toFloat()/photo.width;val scaleY=height.toFloat()/photo.height
        val colors=fit(rows,pixels,width,height,floatArrayOf(photo.fx*scaleX,photo.fy*scaleY,photo.cx*scaleX,photo.cy*scaleY))
        checkCancelled()
        require(colors.size==count*3 && colors.all{it.isFinite() && it in 0f..1f})
        repeat(count){i->for(c in 0..2)data.putFloat(offset+i*56+(3+c)*4,(colors[i*3+c]-.5f)/.28209479177387814f)}
        val partial=File(output.parentFile,output.name+".partial-"+UUID.randomUUID())
        try {partial.writeBytes(bytes);checkCancelled();check(partial.renameTo(output)){"颜色细化缓存保存失败"}}finally{partial.delete()}
        Log.i("SpatialPhoto","PHOTO_COLOR_REFINEMENT count=$count size=${width}x$height ms=${SystemClock.elapsedRealtime()-started} geometryUnchanged=true source=$source output=$output")
        output
        }
    }
}
