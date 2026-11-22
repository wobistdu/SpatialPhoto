package com.example.spatialphoto.ui.capture

import com.example.spatialphoto.domain.model.*

data class CaptureUiState(
    val cameraRequested:Boolean=false,
    val cameraSession:Long=0,
    val cameraStageAttached:Boolean=false,
    val cameraClosing:Boolean=false,
    val cameraNeedsPermission:Boolean=false,
    val cameraPermissionDenied:Boolean=false,
    val cameraFrame:com.example.spatialphoto.data.repository.CameraFrame?=null,
    val cameraMessage:String="正在启动相机…",
    val photos:List<PhotoItem> = emptyList(),
    val selectedId:String? = null,
    val busy:Boolean=false,
    val savingPhoto:Boolean=false,
    val activeId:String?=null,
    val viewing3D:Boolean=false,
    val previewLoading:Boolean=false,
    val previewFailed:Boolean=false,
    val deletingIds:Set<String> = emptySet(),
    val exporting:Boolean=false,
    val exportNeedsPermission:Boolean=false,
    val exportMessage:String="",
    val previewRevision:Long=0,
    val loading:Boolean=true,
    val message:String="正在读取照片…",
    val previewMessage:String="加载 3DGS…",
) {
    val processingCount:Int get()=photos.count{it.processing}
    val waitingCount:Int get()=photos.count{it.waiting}
    val selected:PhotoItem? get()=photos.firstOrNull { it.id==selectedId }
    val scene:GeneratedScene? get()=selected?.scene
}
sealed interface CaptureEvent {
    data object OpenCamera:CaptureEvent
    data class CameraPermissionResult(val granted:Boolean):CaptureEvent
    data object CameraReady:CaptureEvent
    data class CameraDisposed(val session:Long):CaptureEvent
    data object CloseCamera:CaptureEvent
    data object Shutter:CaptureEvent
    data class CameraFailed(val reason:String):CaptureEvent
    data object Capture:CaptureEvent
    data class Open(val id:String):CaptureEvent
    data class Retry(val id:String):CaptureEvent
    data class Delete(val id:String):CaptureEvent
    data class Browse(val direction:Int):CaptureEvent
    data object ExportAll:CaptureEvent
    data class ExportPermissionResult(val granted:Boolean,val message:String=""):CaptureEvent
    data object Enter3D:CaptureEvent
    data object Exit3D:CaptureEvent
    data object Back:CaptureEvent
    data class RenderFailed(val reason:String):CaptureEvent
    data object Rendered:CaptureEvent
}
