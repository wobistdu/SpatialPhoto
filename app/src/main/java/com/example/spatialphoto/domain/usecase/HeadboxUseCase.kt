package com.example.spatialphoto.domain.usecase

import kotlin.math.*

/** Headbox relative to original camera. Limits are demo policy, not reconstruction guarantees. */
object HeadboxUseCase {
    fun allowed(x: Float, y: Float, z: Float, yawDegrees: Float, pitchDegrees: Float): Boolean =
        listOf(x,y,z,yawDegrees,pitchDegrees).all { it.isFinite() } &&
        sqrt(x*x+y*y+z*z)<=0.10f && abs(yawDegrees)<=10f && abs(pitchDegrees)<=8f
}
