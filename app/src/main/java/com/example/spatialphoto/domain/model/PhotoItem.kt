package com.example.spatialphoto.domain.model

/** Actual saved photo and reconstruction, never an inline UI fixture. */
data class PhotoItem(
    val photo: CapturePhoto,
    val scene: GeneratedScene? = null,
    val progress: String? = null,
    val error: String? = null,
    val queued: Boolean = false,
    val queuedAt: Long = 0L,
    val percent: Int? = null,
    /** Runtime-only queue for preparing an existing reconstruction for browsing. */
    val previewQueued: Boolean = false,
    val displayTitle: String? = null,
    val builtInSample: Boolean = false,
) {
    val id: String get() = photo.path
    val waiting: Boolean get() = queued || previewQueued
    val processing: Boolean get() = progress != null && !waiting
    val ready: Boolean get() = scene != null && progress == null && error == null && !waiting
}
