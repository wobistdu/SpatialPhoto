package com.example.spatialphoto.data.repository

import kotlin.math.*

/** Matches PyTorch interpolate bilinear align_corners=true, RGB/0..1/NCHW. */
object SharpImageUseCase {
 fun resizeAlignCorners(pixels:IntArray,width:Int,height:Int,size:Int=512):FloatArray {
  require(width>1 && height>1 && size>1 && pixels.size==width*height)
  val area=size*size;val out=FloatArray(area*3)
  for(y in 0 until size)for(x in 0 until size) {
   val sx=x.toFloat()*(width-1)/(size-1);val sy=y.toFloat()*(height-1)/(size-1)
   val x0=floor(sx).toInt();val y0=floor(sy).toInt();val x1=min(x0+1,width-1);val y1=min(y0+1,height-1)
   val dx=sx-x0;val dy=sy-y0
   for(c in 0..2) {
    val shift=16-c*8
    fun value(xx:Int,yy:Int)=((pixels[yy*width+xx] ushr shift) and 255).toFloat()
    out[c*area+y*size+x]=((value(x0,y0)*(1-dx)+value(x1,y0)*dx)*(1-dy)+(value(x0,y1)*(1-dx)+value(x1,y1)*dx)*dy)/255f
   }
  }
  return out
 }
}
