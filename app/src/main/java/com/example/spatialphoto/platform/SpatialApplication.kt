package com.example.spatialphoto.platform

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.spatialphoto.ui.capture.CaptureViewModelFactory
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.spatialphoto.mainApp

class SpatialApplication : Application(), ViewModelStoreOwner {
    // Both containers share process-lifetime state so closing capture does not cancel reconstruction.
    override val viewModelStore = ViewModelStore()
    val captureViewModelFactory by lazy { CaptureViewModelFactory(this) }

    private val sharpDelegate = lazy<com.example.spatialphoto.data.repository.SharpRepository> {
        com.example.spatialphoto.data.repository.OnnxSharpRepository(this)
    }
    val sharpRepository get() = sharpDelegate.value

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (sharpDelegate.isInitialized() && (level == TRIM_MEMORY_RUNNING_LOW ||
                level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND)) {
            sharpRepository.requestRelease()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (sharpDelegate.isInitialized()) sharpRepository.requestRelease()
    }

    override fun onCreate() {
        super.onCreate()
        if(!getProcessName().endsWith(":migration"))launch(::mainApp)
    }
}
