package com.example.spatialphoto

import com.example.spatialphoto.domain.usecase.GaussianBudget
import com.example.spatialphoto.domain.model.CapturePhoto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GaussianBudgetTest {
    @Test fun reductionKeepsSparseRegionsAndDepthLayersInsteadOfTakingFirstRows() {
        val n=294912
        val u=FloatArray(n){if(it>=n-4).95f else .1f}
        val v=FloatArray(n){if(it>=n-4).95f else .1f}
        val z=FloatArray(n){if(it%2==0)1f else 10f}
        val a=FloatArray(n){if(it>=n-4).01f else .8f}
        val keep=GaussianBudget.select(u,v,z,a)
        assertEquals(250000,keep.size);assertEquals(keep.size,keep.toSet().size)
        assertTrue(keep.any{it>=n-4 && z[it]==1f});assertTrue(keep.any{it>=n-4 && z[it]==10f})
        assertArrayEquals(keep,GaussianBudget.select(u,v,z,a))
        assertTrue((1 until keep.size).all{keep[it-1]<keep[it]})
    }
    @Test fun belowBudgetPreservesAllRowsInOrder() {
        assertArrayEquals(IntArray(128){it},GaussianBudget.select(FloatArray(128),FloatArray(128),FloatArray(128){1f},FloatArray(128){.5f}))
    }
    @Test fun oldPlyBudgetCachePreservesOriginalAndEveryKeptRowExactly() {
        val dir=Files.createTempDirectory("gaussian-budget").toFile()
        try {
            val n=250101
            val fields=listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")
            val header=("ply\nformat binary_little_endian 1.0\nelement vertex $n\n"+fields.joinToString(""){"property float $it\n"}+"end_header\n").toByteArray()
            val rows=ByteBuffer.allocate(n*56).order(ByteOrder.LITTLE_ENDIAN)
            repeat(n){i->rows.putFloat((i%640)/640f-.5f);rows.putFloat(((i/640%480)/480f-.5f)*.5f);rows.putFloat(1f);rows.putFloat(i.toFloat());repeat(10){rows.putFloat((i%31)/31f)}}
            val original=header+rows.array();val source=java.io.File(dir,"scene.ply");source.writeBytes(original)
            val photo=CapturePhoto("",640,480,500f,500f,320f,240f)
            val output=GaussianBudget.prepare(source,photo);val bytes=output.readBytes()
            assertArrayEquals(original,source.readBytes());assertEquals(output,GaussianBudget.prepare(source,photo))
            val offset=bytes.toString(Charsets.ISO_8859_1).indexOf("end_header\n")+11
            assertEquals(250000*56,bytes.size-offset)
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);var previous=-1
            repeat(250000){k->val pos=offset+k*56;val id=b.getFloat(pos+12).toInt();assertTrue(id>previous);previous=id
                for(j in 0 until 56)assertEquals(original[header.size+id*56+j],bytes[pos+j])}
            var checks=0
            output.delete()
            try {GaussianBudget.prepare(source,photo){if(++checks==3)throw java.util.concurrent.CancellationException()};fail("cancel expected")}catch(_:java.util.concurrent.CancellationException){}
            assertEquals(listOf("scene.ply"),dir.listFiles()!!.map{it.name})
        }finally{dir.deleteRecursively()}
    }
}
