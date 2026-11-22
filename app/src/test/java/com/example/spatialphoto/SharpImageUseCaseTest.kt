package com.example.spatialphoto
import com.example.spatialphoto.data.repository.SharpImageUseCase
import org.junit.Assert.*
import org.junit.Test
class SharpImageUseCaseTest {
 @Test fun keepsCornerRgb(){val p=intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt());val a=SharpImageUseCase.resizeAlignCorners(p,2,2,3);assertEquals(1f,a[0],0f);assertEquals(0f,a[9],0f);assertEquals(1f,a[8],0f);assertEquals(1f,a[17],0f);assertEquals(1f,a[26],0f)}
 @Test fun centerIsBilinear(){val p=intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xffffffff.toInt(),0xff000000.toInt());val a=SharpImageUseCase.resizeAlignCorners(p,2,2,3);assertEquals(.5f,a[4],1e-6f);assertEquals(.5f,a[13],1e-6f);assertEquals(.5f,a[22],1e-6f)}
}
