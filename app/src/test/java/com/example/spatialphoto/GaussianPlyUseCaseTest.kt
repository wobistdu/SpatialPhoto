package com.example.spatialphoto

import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.GaussianPlyUseCase
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GaussianPlyUseCaseTest {
 private fun fixture(): GaussianOutput = GaussianOutput(FloatArray(303){ if(it%3==2)1f else 0f },FloatArray(303){.01f},FloatArray(404){if(it%4==0)1f else 0f},FloatArray(303){.5f},FloatArray(101){.5f})
 @Test fun canonicalFileRoundtripLayout() { val out=File.createTempFile("sharp-test",".ply");try {assertEquals(101,GaussianPlyUseCase.write(fixture(),CapturePhoto("",512,512,256f,256f,256f,256f),out));val bytes=out.readBytes();val all=bytes.toString(Charsets.ISO_8859_1);val header=all.indexOf("end_header\n")+11;assertEquals(header+101*56,bytes.size);assertTrue(all.contains("element vertex 101"))}finally{out.delete()} }
 @Test fun nonCenteredIntrinsicsOffsetSign() {
  val out=File.createTempFile("sharp-offcenter",".ply")
  try {val g=fixture();for(i in 0..100)g.means[i*3+2]=3f
   GaussianPlyUseCase.write(g,CapturePhoto("",512,512,256f,256f,300f,200f),out)
   val bytes=out.readBytes();val offset=bytes.toString(Charsets.ISO_8859_1).indexOf("end_header\n")+11
   val b=java.nio.ByteBuffer.wrap(bytes,offset,56).order(java.nio.ByteOrder.LITTLE_ENDIAN)
   assertEquals(-44f/256f*3,b.float,1e-6f);assertEquals(56f/256f*3,b.float,1e-6f)
  }finally{out.delete()}
 }
 @Test fun anisotropicRotationPreservesCovariance() {
  val g=fixture();for(i in 0..100){g.scales[i*3]=.1f;g.scales[i*3+1]=.02f;g.scales[i*3+2]=.003f;g.rotations[i*4]=0f;g.rotations[i*4+1]=1f}
  val out=File.createTempFile("sharp-rotation",".ply")
  try {assertEquals(101,GaussianPlyUseCase.write(g,CapturePhoto("",512,512,256f,256f,256f,256f),out))
   val bytes=out.readBytes();val offset=bytes.toString(Charsets.ISO_8859_1).indexOf("end_header\n")+11
   val b=java.nio.ByteBuffer.wrap(bytes,offset+7*4,7*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
   val scales=FloatArray(3){kotlin.math.exp(b.float)}.sorted();assertEquals(.003f,scales[0],1e-6f);assertEquals(.1f,scales[2],1e-6f)
   val q=FloatArray(4){b.float};assertEquals(1f,q.sumOf{(it*it).toDouble()}.toFloat(),1e-5f)
  }finally{out.delete()}
 }
 @Test fun invalidNumberRejected() {val g=fixture();g.means[0]=Float.NaN;try{GaussianPlyUseCase.validate(g);fail("must reject NaN")}catch(_:IllegalArgumentException){}}
 @Test fun invalidScaleRejected() {val g=fixture();g.scales[0]=0f;try{GaussianPlyUseCase.validate(g);fail("must reject scale")}catch(_:IllegalArgumentException){}}
}
