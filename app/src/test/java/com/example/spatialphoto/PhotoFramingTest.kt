package com.example.spatialphoto
import com.example.spatialphoto.domain.usecase.PhotoFraming
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class PhotoFramingTest {
 @Test fun calibratedCropContainsExactlyThePreviewAndPreservesRays(){
  val fx=1221.0383f;val c=PhotoFraming.crop(1920,1440,fx,959.5f,719.5f)
  assertEquals(4*c.height,3*c.width)
  val fov=2*atan(c.width/(2.0*fx))*180/PI
  assertTrue(fov in 59.8..60.0)
  for(u in listOf(0f,c.width/2f,c.width-1f))assertEquals((u+c.left-959.5f)/fx,(u-c.cx)/fx,1e-7f)
  assertTrue(c.left>=0 && c.top>=0 && c.left+c.width<=1920 && c.top+c.height<=1440)
 }
 @Test fun narrowerSensorIsNeverPaddedOrUpscaled(){
  val c=PhotoFraming.crop(640,480,1000f,300f,220f)
  assertEquals(640,c.width);assertEquals(480,c.height)
  assertEquals(0,c.left);assertEquals(0,c.top)
 }
}
