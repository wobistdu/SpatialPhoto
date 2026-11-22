package com.example.spatialphoto.domain.model

enum class GaussianSource { SHARP_NDC, DEPTH_SMALL_CAMERA }

/** Linear RGB Gaussian parameters with an explicit coordinate convention. */
data class GaussianOutput(
    val means: FloatArray, val scales: FloatArray, val rotations: FloatArray,
    val colors: FloatArray, val opacities: FloatArray,
    val inputResolution: Int = if(opacities.size>131072)768 else 512,
    val source: GaussianSource = GaussianSource.SHARP_NDC,
) {
    val count: Int get() = opacities.size
}

data class CapturePhoto(val path: String, val width: Int, val height: Int,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float)
data class GeneratedScene(val path: String, val count: Int, val generationMs: Long, val resolution:Int=512,
    val source: GaussianSource = GaussianSource.SHARP_NDC)
