package com.example.spatialphoto.domain.model

/** SHARP's five actual outputs, before camera unprojection. */
data class GaussianOutput(
    val means: FloatArray, val scales: FloatArray, val rotations: FloatArray,
    val colors: FloatArray, val opacities: FloatArray,
) {
    val count: Int get() = opacities.size
    val inputResolution: Int get() = if(count>131072)768 else 512
}

data class CapturePhoto(val path: String, val width: Int, val height: Int,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float)
data class GeneratedScene(val path: String, val count: Int, val generationMs: Long, val resolution:Int=512)
