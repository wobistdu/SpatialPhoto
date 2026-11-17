package com.example.spatialphoto.domain.usecase

import kotlin.math.*

/** A moderate landscape camera crop. Principal point and focal lengths stay calibrated. */
object PhotoFraming {
    const val HORIZONTAL_FOV_DEGREES=60.0
    const val ASPECT=4.0/3.0
    const val VIEWFINDER_DISTANCE=1.1f
    // Keep the complete 4:3 preview inside the attachment texture budget. The photo's
    // optical FOV is defined by the calibrated crop, independently of preview window size.
    const val VIEWFINDER_LAYOUT_WIDTH_DP=960f
    data class Crop(val left:Int,val top:Int,val width:Int,val height:Int,val cx:Float,val cy:Float)
    fun crop(width:Int,height:Int,fx:Float,cx:Float,cy:Float):Crop {
        require(width>0 && height>0 && fx.isFinite() && fx>0 && cx.isFinite() && cy.isFinite())
        val wanted=2*fx*tan(Math.toRadians(HORIZONTAL_FOV_DEGREES/2))
        val units=floor(minOf(wanted/4,width/4.0,height/3.0)).toInt().coerceAtLeast(1)
        val w=units*4;val h=units*3
        require(w<=width && h<=height)
        val left=(cx-(w-1)/2f).roundToInt().coerceIn(0,width-w)
        val top=(cy-(h-1)/2f).roundToInt().coerceIn(0,height-h)
        return Crop(left,top,w,h,cx-left,cy-top)
    }
}
