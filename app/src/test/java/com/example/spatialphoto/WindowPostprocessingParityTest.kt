package com.example.spatialphoto

import com.example.spatialphoto.benchmark.baseline.*
import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import kotlin.random.Random

class WindowPostprocessingParityTest {
 @Test fun completeWindowFilesStayByteIdentical() {
  val r=Random(2347);val n=9000
  val g=GaussianOutput(FloatArray(n*3){if(it%3==2).04f+r.nextFloat()*80 else r.nextFloat()*8-4},
   FloatArray(n*3){.000001f+r.nextFloat()*.2f},FloatArray(n*4){r.nextFloat()*2-1},
   FloatArray(n*3){r.nextFloat()},FloatArray(n){r.nextFloat()})
  val root=kotlin.io.path.createTempDirectory("window-parity").toFile()
  try {
   for(photo in listOf(CapturePhoto("",1408,1056,1221f,1221f,704f,528f),CapturePhoto("",1920,1080,1150f,1190f,920f,500f))) {
    val oldDir=File(root,"old").apply{mkdirs()};val newDir=File(root,"new").apply{mkdirs()}
    val source=File(oldDir,"scene.ply");FrozenGaussianPly22.write(g,photo,source)
    val copy=File(newDir,"scene.ply");source.copyTo(copy,overwrite=true)
    val expected=FrozenWindowGeometry22.prepare(source,photo).readBytes()
    val actual=WindowPhotoGeometry.prepare(copy,photo)
    assertArrayEquals(expected,actual.readBytes())
    assertEquals(actual,WindowPhotoGeometry.prepare(copy,photo))
    oldDir.deleteRecursively();newDir.deleteRecursively()
   }
  } finally {root.deleteRecursively()}
 }
}
