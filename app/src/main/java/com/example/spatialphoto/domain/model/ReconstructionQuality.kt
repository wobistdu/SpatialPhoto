package com.example.spatialphoto.domain.model

/** Shared production policy for inference and the initial estimated progress duration. */
object ReconstructionQuality {
    const val INPUT_RESOLUTION = 768
    val ACTIVE_INPUT_RESOLUTION = if(com.example.spatialphoto.BuildConfig.DEPTH_SMALL_MODEL)518 else INPUT_RESOLUTION
    val INITIAL_ESTIMATE_MS = if(com.example.spatialphoto.BuildConfig.DEPTH_SMALL_MODEL)15_000L else 65_000L
}
