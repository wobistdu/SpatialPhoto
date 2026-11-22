package com.example.spatialphoto

import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.GaussianPlyUseCase
import com.example.spatialphoto.domain.usecase.GaussianBudget
import com.example.spatialphoto.benchmark.BaselineGaussianPly
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.math.pow

class PostprocessingParityTest {
    private fun compare(g: GaussianOutput, photo: CapturePhoto) {
        val old=File.createTempFile("sharp-old", ".ply")
        val new=File.createTempFile("sharp-new", ".ply")
        try {
            val oldCount=BaselineGaussianPly.write(g, photo, old)
            val newCount=GaussianPlyUseCase.write(g, photo, new)
            fun body(file:File):ByteArray {
                val bytes=file.readBytes()
                val marker="end_header\n".toByteArray()
                val offset=(0..bytes.size-marker.size).first { start ->
                    marker.indices.all { bytes[start+it]==marker[it] }
                }+marker.size
                return bytes.copyOfRange(offset,bytes.size)
            }
            val before=body(old);val after=body(new)
            assertEquals(oldCount*56,before.size)
            assertEquals(newCount*56,after.size)
            // Independently identify legal reference rows. The budget may discard rows,
            // but each retained covariance/color/opacity must stay byte-identical.
            val eligible=ArrayList<Int>()
            var baselineRow=0
            for(i in 0 until g.count) {
                val z=g.means[i*3+2]
                if(z>.03f && z<100f && g.opacities[i]>.005f) {
                    if(kotlin.math.abs(g.means[i*3])<z && kotlin.math.abs(g.means[i*3+1])<z)
                        eligible.add(baselineRow)
                    baselineRow++
                }
            }
            assertEquals(oldCount,baselineRow)
            assertEquals(minOf(eligible.size,GaussianBudget.MAX_COUNT),newCount)
            var cursor=0
            for(row in 0 until newCount) {
                while(cursor<eligible.size && !(0 until 56).all {
                    before[eligible[cursor]*56+it]==after[row*56+it]
                }) cursor++
                assertTrue("Retained row $row differs from reference encoding/order",cursor<eligible.size)
                cursor++
            }
            if(eligible.size<=GaussianBudget.MAX_COUNT) assertEquals(eligible.size,cursor)
        } finally { old.delete();new.delete() }
    }
    @Test fun actualModelRetainedRowsAreByteIdentical() {
        val b=ByteBuffer.wrap(javaClass.classLoader!!.getResourceAsStream("sharp128.bin")!!.use{it.readBytes()}).order(ByteOrder.LITTLE_ENDIAN)
        fun read(n:Int)=FloatArray(n){b.float}
        val g=GaussianOutput(read(384),read(384),read(512),read(384),read(128))
        compare(g,CapturePhoto("",1920,1440,1221f,1197f,945f,700f))
    }
    @Test fun fullSizeBudgetRetainsByteIdenticalAnisotropicAndBoundaryRows() {
        val r=Random(801);val n=294912
        val g=GaussianOutput(FloatArray(n*3){if(it%3==2)r.nextFloat()*110 else r.nextFloat()*4-2},
            FloatArray(n*3){10.0.pow(-6+r.nextDouble()*6).toFloat()},
            FloatArray(n*4){r.nextFloat()*2-1},FloatArray(n*3){r.nextFloat()*1.2f-.1f},FloatArray(n){r.nextFloat()})
        // Degenerate eigenvalues, axis rotations, filtering thresholds and both sRGB branches.
        for(i in 0..15) {
            for(k in 0..3)g.rotations[i*4+k]=if(k==i%4)1f else 0f
            for(k in 0..2)g.scales[i*3+k]=.01f
            g.means[i*3+2]=floatArrayOf(.03f,100f,1f,2f)[i%4]
            g.opacities[i]=floatArrayOf(0f,.005f,1f,.5f)[i%4]
            g.colors[i*3]=.0031308f
        }
        compare(g,CapturePhoto("",1920,1440,1221f,1197f,945f,700f))
        compare(g,CapturePhoto("",512,512,256f,256f,256f,256f))
    }
}
