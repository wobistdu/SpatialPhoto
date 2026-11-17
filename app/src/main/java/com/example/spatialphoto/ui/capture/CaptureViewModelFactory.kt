package com.example.spatialphoto.ui.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.spatialphoto.data.repository.FilePhotoLibraryRepository
import com.example.spatialphoto.data.repository.GalleryPhotoExporter
import com.example.spatialphoto.data.repository.MigratingPhotoLibraryRepository
import com.example.spatialphoto.data.repository.VstPhotoRepository
import com.example.spatialphoto.domain.usecase.GenerateSceneUseCase
import com.example.spatialphoto.platform.SpatialApplication
import java.io.File

class CaptureViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CaptureViewModel::class.java))
        val app = context.applicationContext as SpatialApplication
        val camera = VstPhotoRepository(app)
        val stored = FilePhotoLibraryRepository(File(app.filesDir, "captures"))
        val library = MigratingPhotoLibraryRepository(app, stored)
        val generate = GenerateSceneUseCase(camera, app.sharpRepository, library)
        @Suppress("UNCHECKED_CAST")
        return CaptureViewModel(generate, camera, exporter = GalleryPhotoExporter(app)) as T
    }
}
