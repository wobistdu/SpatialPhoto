package com.example.spatialphoto.benchmark.baseline

import com.example.spatialphoto.domain.usecase.GaussianBudget

import com.example.spatialphoto.domain.model.CapturePhoto
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

/** A view-preserving depth remap of real anisotropic Gaussians, not a depth mesh or image plane.
 * Units are photo widths. The original metric PLY is never modified. Native rendering supplies
 * the actual two eye views. Depth is compressed for comfortable bounded-window presentation.
 */
object FrozenWindowGeometry22 {
    // Nominal eye distance / displayed photo width for the enlarged SharedSpace window.
    // Device window: distance ~1.74m, effective width ~0.87m. This is not live head tracking.
    const val REFERENCE_DISTANCE = 2.0
    // The 140dp toolbar reserve puts the photo center 70dp above the window center.
    const val REFERENCE_EYE_Y = -0.10
    const val HALF_DEPTH = 0.15
    const val DEPTH_GAIN = 2.5
    data class Mapping(val point:DoubleArray,val jacobian:Array<DoubleArray>)
    fun map(x:Double,y:Double,z:Double,referenceDepth:Double,photo:CapturePhoto):Mapping {
        require(x.isFinite() && y.isFinite() && z.isFinite() && z>0 && referenceDepth.isFinite() && referenceDepth>0)
        val t=tanh(DEPTH_GAIN*ln(z/referenceDepth))
        val depth=-HALF_DEPTH*t
        val dz=-HALF_DEPTH*DEPTH_GAIN*(1-t*t)/z
        val f=1-depth/REFERENCE_DISTANCE
        val df=-dz/REFERENCE_DISTANCE
        val u=(photo.fx*x/z+photo.cx-photo.width/2.0)/photo.width
        val v=-(photo.fy*y/z+photo.cy-photo.height/2.0)/photo.width
        val j=arrayOf(
            doubleArrayOf(photo.fx/photo.width.toDouble()/z*f,0.0,-photo.fx*x/photo.width/z.pow(2)*f+u*df),
            doubleArrayOf(0.0,-photo.fy/photo.width.toDouble()/z*f,photo.fy*y/photo.width/z.pow(2)*f+(v-REFERENCE_EYE_Y)*df),
            doubleArrayOf(0.0,0.0,dz))
        return Mapping(doubleArrayOf(u*f,v*f+REFERENCE_EYE_Y*(1-f),depth),j)
    }
    fun prepare(source:File,photo:CapturePhoto,checkCancelled:()->Unit={}):File {
        checkCancelled()
        require(photo.width>0 && photo.height>0 && photo.fx.isFinite() && photo.fy.isFinite() && photo.fx>0 && photo.fy>0 && photo.cx.isFinite() && photo.cy.isFinite()) { "Invalid photo calibration" }
        require(source.length() in 1..(300000L*56+4096)) { "Gaussian file exceeds window budget" }
        val bytes=source.readBytes()
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.update("v8:${photo.width}:${photo.height}:${photo.fx}:${photo.fy}:${photo.cx}:${photo.cy}:$DEPTH_GAIN:$HALF_DEPTH:$REFERENCE_DISTANCE:$REFERENCE_EYE_Y".toByteArray())
        val key=digest.digest().take(12).joinToString(""){"%02x".format(it.toInt() and 255)}
        val output=File(source.parentFile,"window-photo-v8-$key.ply")
        if(output.isFile && output.length()>4096)return output
        val end="end_header\n".toByteArray()
        var offset=-1
        for(i in 0 until minOf(4096,bytes.size-end.size+1)) {
            if(end.indices.all{bytes[i+it]==end[it]}){offset=i+end.size;break}
        }
        require(offset>0){"Invalid Gaussian header"}
        val header=bytes.copyOfRange(0,offset).toString(Charsets.US_ASCII)
        require(header.contains("format binary_little_endian 1.0"))
        val fields=header.lineSequence().filter{it.startsWith("property ")}.toList()
        val expected=listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")
        require(fields==expected.map{"property float $it"}){"Unsupported Gaussian schema"}
        val count=header.lineSequence().first{it.startsWith("element vertex ")}.substringAfterLast(' ').toInt()
        require(count in 101..300000 && bytes.size-offset==count*56)
        val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val depths=DoubleArray(count){input.getFloat(offset+it*56+8).toDouble().also{z->require(z.isFinite() && z>0)}}.sorted()
        val median=depths[count/2]
        val outputData=ByteBuffer.allocate(count*56).order(ByteOrder.LITTLE_ENDIAN)
        val row=FloatArray(14)
        repeat(count){i ->
            if(i%256==0)checkCancelled()
            input.position(offset+i*56);for(k in 0..13)row[k]=input.float
            require(row.all{it.isFinite()})
            val sourceU=photo.fx*row[0]/row[2]+photo.cx
            val sourceV=photo.fy*row[1]/row[2]+photo.cy
            val edge=minOf(sourceU/photo.width,1-sourceU/photo.width,sourceV/photo.height,1-sourceV/photo.height)
            if(edge<=0f)return@repeat
            val fade=(edge/.015f).coerceIn(0f,1f).let{it*it*(3-2*it)}
            val alpha=(1.0/(1+exp(-row[6].toDouble()))*fade).coerceIn(1e-8,1-1e-8)
            val m=map(row[0].toDouble(),row[1].toDouble(),row[2].toDouble(),median,photo)
            val cov=FrozenGaussianPly22.covariance(row.copyOfRange(10,14),FloatArray(3){exp(row[7+it])})
            val j=m.jacobian
            val mapped=Array(3){a->DoubleArray(3){b->(0..2).sumOf{u->(0..2).sumOf{v->j[a][u]*cov[u][v]*j[b][v]}}}}
            val (scale,q)=FrozenGaussianPly22.eigen(mapped)
            m.point.forEach{outputData.putFloat(it.toFloat())}
            for(k in 3..5)outputData.putFloat(row[k])
            outputData.putFloat(ln(alpha/(1-alpha)).toFloat())
            scale.forEach{outputData.putFloat(ln(it.coerceAtLeast(1e-8)).toFloat())}
            q.forEach{outputData.putFloat(it.toFloat())}
        }
        val partial=File(output.parentFile,output.name+".partial-"+UUID.randomUUID())
        val retained=outputData.position()/56
        require(retained>100){"Too few Gaussians inside the photograph"}
        val derivedHeader=header.replace("element vertex $count","element vertex $retained")
        try {
            checkCancelled()
            partial.outputStream().buffered().use{out->out.write(derivedHeader.toByteArray(Charsets.US_ASCII));out.write(outputData.array(),0,outputData.position())}
            checkCancelled()
            check(partial.renameTo(output)){"Window Gaussian save failed"}
        } finally { partial.delete() }
        return output
    }
}
