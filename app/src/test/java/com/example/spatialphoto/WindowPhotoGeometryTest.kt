package com.example.spatialphoto

import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.*

class WindowPhotoGeometryTest {
    private val photo=CapturePhoto("",1920,1440,1221f,1221f,959.5f,719.5f)
    @Test fun originalCalibratedPixelsSurviveDepthMapping(){
        for(z in listOf(.08,.4,1.0,2.0,10.0,80.0))for(u in listOf(0.0,320.0,960.0,1700.0,1919.0)){
            val v=350.0
            val p=WindowPhotoGeometry.map((u-photo.cx)*z/photo.fx,(v-photo.cy)*z/photo.fy,z,1.2,photo).point
            val f=1-p[2]/WindowPhotoGeometry.REFERENCE_DISTANCE
            assertEquals(u,photo.width/2.0+photo.width*p[0]/f,1e-8)
            assertEquals(v,photo.height/2.0-photo.width*(WindowPhotoGeometry.REFERENCE_EYE_Y+(p[1]-WindowPhotoGeometry.REFERENCE_EYE_Y)/f),1e-8)
        }
    }
    @Test fun jacobianMatchesIndependentFiniteDifferences(){
        val p=doubleArrayOf(.2,-.3,1.7)
        val a=WindowPhotoGeometry.map(p[0],p[1],p[2],1.2,photo)
        for(axis in 0..2){
            val plus=p.clone();plus[axis]+=1e-5
            val minus=p.clone();minus[axis]-=1e-5
            val hi=WindowPhotoGeometry.map(plus[0],plus[1],plus[2],1.2,photo).point
            val lo=WindowPhotoGeometry.map(minus[0],minus[1],minus[2],1.2,photo).point
            for(row in 0..2)assertEquals((hi[row]-lo[row])/2e-5,a.jacobian[row][axis],1e-8)
        }
    }
    @Test fun depthRemainsOrderedAndBounded(){
        var previous=Double.POSITIVE_INFINITY
        for(z in listOf(.03,.3,.8,1.2,2.5,10.0,99.0)){
            val depth=WindowPhotoGeometry.map(0.0,0.0,z,1.2,photo).point[2]
            assertTrue(depth<previous);assertTrue(abs(depth)<WindowPhotoGeometry.HALF_DEPTH)
            previous=depth
        }
    }
    @Test fun nearAndFarPointsHaveDifferentBinocularDisparity(){
        val distance=WindowPhotoGeometry.REFERENCE_DISTANCE
        val positions=listOf(.5,2.0).map{WindowPhotoGeometry.map(0.0,0.0,it,1.0,photo).point}
        fun disparity(p:DoubleArray):Double {
            fun project(eye:Double)=eye+(p[0]-eye)*distance/(distance-p[2])
            return project(-.075)-project(.075)
        }
        assertTrue(abs(disparity(positions[0])-disparity(positions[1]))>.02)
    }
    @Test fun derivedPlyPreservesSourceAndPhotometricValues(){
        val dir=Files.createTempDirectory("window-photo-test").toFile()
        try {
            val g=GaussianOutput(FloatArray(303){if(it%3==2)1f+it/303f else .1f},FloatArray(303){.01f},FloatArray(404){if(it%4==0)1f else 0f},FloatArray(303){.5f},FloatArray(101){.6f})
            val source=java.io.File(dir,"scene.ply")
            GaussianPlyUseCase.write(g,photo,source)
            val original=source.readBytes()
            val derived=WindowPhotoGeometry.prepare(source,photo).readBytes()
            assertArrayEquals(original,source.readBytes())
            val offset=original.toString(Charsets.ISO_8859_1).indexOf("end_header\n")+11
            val before=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
            val after=ByteBuffer.wrap(derived).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0..100){
                for(k in 3..6)assertEquals(before.getFloat(offset+i*56+k*4),after.getFloat(offset+i*56+k*4),0f)
                for(k in 0..13)assertTrue(after.getFloat(offset+i*56+k*4).isFinite())
            }
        }finally{dir.deleteRecursively()}
    }

    private fun fixture(dir:java.io.File):java.io.File {
        val g=GaussianOutput(FloatArray(303){if(it%3==2)1.2f else .1f},FloatArray(303){.01f},FloatArray(404){if(it%4==0)1f else 0f},FloatArray(303){.5f},FloatArray(101){.6f})
        return java.io.File(dir,"scene.ply").also{GaussianPlyUseCase.write(g,photo,it)}
    }
    @Test fun cacheChangesWhenCalibrationChanges(){
        val dir=Files.createTempDirectory("window-cache-test").toFile()
        try {
            val source=fixture(dir)
            val first=WindowPhotoGeometry.prepare(source,photo)
            assertEquals(first,WindowPhotoGeometry.prepare(source,photo))
            val changed=WindowPhotoGeometry.prepare(source,photo.copy(fx=photo.fx*1.1f))
            assertNotEquals(first,changed)
            assertFalse(first.readBytes().contentEquals(changed.readBytes()))
        }finally{dir.deleteRecursively()}
    }
    @Test fun cancellationLeavesNoDerivedOrPartialFile(){
        val dir=Files.createTempDirectory("window-cancel-test").toFile()
        try {
            val source=fixture(dir);var checks=0
            try {
                WindowPhotoGeometry.prepare(source,photo){if(++checks==3)throw java.util.concurrent.CancellationException()}
                fail("Expected cancellation")
            }catch(expected:java.util.concurrent.CancellationException){}
            assertEquals(listOf("scene.ply"),dir.listFiles()!!.map{it.name})
        }finally{dir.deleteRecursively()}
    }
    @Test fun corruptDepthIsRejectedBeforeMapping(){
        val dir=Files.createTempDirectory("window-invalid-test").toFile()
        try {
            val source=fixture(dir);val bytes=source.readBytes()
            val offset=bytes.toString(Charsets.ISO_8859_1).indexOf("end_header\n")+11
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset+8,Float.NaN)
            source.writeBytes(bytes)
            try {WindowPhotoGeometry.prepare(source,photo);fail("Expected rejection")}
            catch(expected:IllegalArgumentException){}
            assertEquals(listOf("scene.ply"),dir.listFiles()!!.map{it.name})
        }finally{dir.deleteRecursively()}
    }
}
