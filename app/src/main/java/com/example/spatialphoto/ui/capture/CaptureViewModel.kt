package com.example.spatialphoto.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.SceneGenerator
import com.example.spatialphoto.data.repository.CameraFrame
import com.example.spatialphoto.data.repository.CameraPreviewSource

/** Capture saves independently; exactly one application-owned worker drains the durable FIFO. */
class CaptureViewModel(private val generate:SceneGenerator,
    private val camera:CameraPreviewSource?=null,
    private val exporter:com.example.spatialphoto.domain.usecase.PhotoExporter?=null,
    private val elapsed:()->Long={android.os.SystemClock.elapsedRealtime()},
    private val canUseCamera:()->Boolean={true},
):ViewModel() {
    private var previewJob:Job?=null
    private var worker:Job?=null
    private val queue=ArrayDeque<CapturePhoto>()
    private val previewQueue=ArrayDeque<PhotoItem>()
    private var estimatedMs=ReconstructionQuality.INITIAL_ESTIMATE_MS
    private val _state=MutableStateFlow(CaptureUiState())
    val state=_state.asStateFlow()
    init { viewModelScope.launch {
        try {
            val photos=generate.listPhotos()
            estimatedMs=photos.mapNotNull{it.scene?.takeIf{s->s.resolution==ReconstructionQuality.ACTIVE_INPUT_RESOLUTION}?.generationMs}.filter{it>0}.take(5).average().takeIf{it.isFinite()}?.toLong() ?: estimatedMs
            photos.filter{it.ready}.forEach{previewQueue.addLast(it)}
            _state.update{it.copy(photos=photos.map{p->if(p.ready)p.copy(previewQueued=true,progress=null,percent=null) else p},loading=false,message="")}
            photos.filter{it.queued}.sortedBy{it.queuedAt}.forEach{queue.addLast(it.photo)}
            drain()
        } catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){_state.update{it.copy(loading=false,message="照片列表读取失败：${failure.message}")}}
    } }
    fun onEvent(e:CaptureEvent) {when(e) {
        CaptureEvent.OpenCamera -> if(!_state.value.cameraRequested && !_state.value.cameraClosing && !_state.value.cameraNeedsPermission && !_state.value.savingPhoto && !_state.value.loading && _state.value.deletingIds.isEmpty()) {
            if(!canUseCamera()) {
                _state.update{it.copy(cameraNeedsPermission=true,message="拍摄需要相机和空间数据权限，照片在本机处理。")}
                return
            }
            _state.update{it.copy(cameraRequested=true,cameraPermissionDenied=false,cameraSession=it.cameraSession+1,cameraFrame=null,cameraMessage="正在准备取景…",message="")}
            startPreview()
        }
        is CaptureEvent.CameraPermissionResult -> {
            _state.update{it.copy(cameraNeedsPermission=false,cameraPermissionDenied=!e.granted,
                message=if(e.granted)"" else "尚未获得拍摄权限。可重新点「拍照」，或前往系统设置 → 应用 → 权限管理，为「空间拍照」开启相机和空间数据权限。")}
            if(e.granted)onEvent(CaptureEvent.OpenCamera)
        }
        CaptureEvent.CameraReady -> _state.update{it.copy(cameraStageAttached=true)}
        is CaptureEvent.CameraDisposed -> if(e.session==_state.value.cameraSession) {
            previewJob?.cancel();previewJob=null
            _state.update{it.copy(cameraRequested=false,cameraStageAttached=false,cameraClosing=false,cameraFrame=null)}
        }
        CaptureEvent.CloseCamera -> {
            previewJob?.cancel();previewJob=null
            _state.update{it.copy(cameraRequested=false,cameraClosing=it.cameraStageAttached,cameraFrame=null)}
        }
        is CaptureEvent.CameraFailed -> {
            previewJob?.cancel();previewJob=null
            _state.update{it.copy(cameraRequested=false,cameraClosing=it.cameraStageAttached,cameraFrame=null,message=e.reason)}
        }
        CaptureEvent.Shutter -> {
            val frame=_state.value.cameraFrame ?: return
            if(!_state.value.cameraRequested || elapsed()-frame.receivedAt>1500L)return
            capture(frame)
        }
        CaptureEvent.Capture -> capture()
        is CaptureEvent.Retry -> {
            val item=_state.value.photos.firstOrNull{it.id==e.id} ?: return
            if(item.waiting || item.id==_state.value.activeId)return
            if(item.scene!=null && item.error!=null && item.progress==null && e.id !in _state.value.deletingIds) {
                updatePhoto(item.photo){it.copy(previewQueued=true,progress=null,percent=null,error=null)}
                previewQueue.addLast(item);drain();return
            }
            if(!item.ready && item.progress==null && e.id !in _state.value.deletingIds) {
                updatePhoto(item.photo){it.copy(progress="等待重建",queued=true,queuedAt=System.currentTimeMillis(),error=null)}
                viewModelScope.launch {
                    try { generate.markQueued(item.photo);queue.addLast(item.photo);drain() }
                    catch(c:CancellationException){throw c}
                    catch(f:Exception){updatePhoto(item.photo){it.copy(progress=null,queued=false,error="加入队列失败：${f.message}")}}
                }
            }
        }
        is CaptureEvent.Delete -> delete(e.id)
        is CaptureEvent.Open -> if(e.id !in _state.value.deletingIds && _state.value.photos.any{it.id==e.id})
            _state.update{it.copy(selectedId=e.id,viewing3D=it.photos.first{p->p.id==e.id}.ready,previewLoading=it.photos.first{p->p.id==e.id}.ready,previewFailed=false,previewRevision=it.previewRevision+1,previewMessage="正在打开空间照片…")}
        is CaptureEvent.Browse -> browse(e.direction)
        CaptureEvent.ExportAll -> exportAll()
        is CaptureEvent.ExportPermissionResult -> {
            _state.update{it.copy(exportNeedsPermission=false,exportMessage=e.message)}
            if(e.granted)exportAll()
        }
        CaptureEvent.Enter3D -> if(_state.value.selected?.ready==true)
            _state.update{it.copy(viewing3D=true,previewLoading=true,previewFailed=false,previewRevision=it.previewRevision+1,previewMessage="正在打开空间照片…")}
        CaptureEvent.Exit3D -> _state.update{it.copy(viewing3D=false,previewLoading=false)}
        CaptureEvent.Back -> _state.update{if(it.viewing3D)it.copy(viewing3D=false,previewLoading=false) else it.copy(selectedId=null)}
        is CaptureEvent.RenderFailed -> _state.update{it.copy(previewLoading=false,previewFailed=true,previewMessage="3D 加载失败：${e.reason}")}
        CaptureEvent.Rendered -> _state.update{it.copy(previewLoading=false,previewFailed=false,previewMessage="轻轻移动头部，感受照片深度")}
    } }
    private fun browse(direction:Int) {
        if(direction!=1 && direction!=-1)return
        _state.update { current ->
            val photos=current.photos.filter{it.id !in current.deletingIds}
            val index=photos.indexOfFirst{it.id==current.selectedId}
            val next=photos.getOrNull(index+direction)
            if(index<0 || next==null)current else {
                val spatial=current.viewing3D && next.ready
                current.copy(selectedId=next.id,viewing3D=spatial,previewLoading=spatial,previewFailed=false,
                    previewRevision=current.previewRevision+1,previewMessage="正在打开空间照片…")
            }
        }
    }
    private fun exportAll() {
        val service=exporter ?: return
        val current=_state.value
        if(current.exporting || current.loading || current.exportNeedsPermission || current.deletingIds.isNotEmpty() || current.photos.isEmpty())return
        if(!service.hasDestination()) {
            _state.update{it.copy(exportNeedsPermission=true,exportMessage="请选择相册目录，授权一次即可")};return
        }
        val snapshot=current.photos.toList()
        _state.update{it.copy(exporting=true,exportMessage="准备导出…")}
        viewModelScope.launch {
            try {
                val result=service.export(snapshot){done,total->_state.update{it.copy(exportMessage="正在导出 $done / $total")}}
                _state.update{it.copy(exportMessage=result)}
            } catch(c:CancellationException){throw c}
            catch(f:Exception){_state.update{it.copy(exportMessage="导出未完成：${f.message}，可重试")}}
            finally {_state.update{it.copy(exporting=false)}}
        }
    }
    private fun delete(id:String) {
        val current=_state.value
        val item=current.photos.firstOrNull{it.id==id} ?: return
        if(current.loading || current.exporting || id in current.deletingIds)return
        queue.removeAll{it.path==id}
        previewQueue.removeAll{it.id==id}
        val activeWorker=worker.takeIf{current.activeId==id}
        // Cancel synchronously before a queued completion can advance this worker to another photo.
        activeWorker?.cancel()
        _state.update{it.copy(deletingIds=it.deletingIds+id,selectedId=if(it.selectedId==id)null else it.selectedId,viewing3D=if(it.selectedId==id)false else it.viewing3D)}
        viewModelScope.launch {
            try {
                activeWorker?.cancelAndJoin()
                if(worker===activeWorker)worker=null
                val warning=generate.deletePhoto(item.photo)
                _state.update{it.copy(photos=it.photos.filterNot{p->p.id==id},deletingIds=it.deletingIds-id,message=warning ?: "")}
            } catch(c:CancellationException){_state.update{it.copy(deletingIds=it.deletingIds-id)};throw c}
            catch(f:Exception){_state.update{it.copy(deletingIds=it.deletingIds-id,message="删除失败：${f.message}",photos=it.photos.map{p->if(p.id==id && (p.progress!=null || p.waiting))p.copy(progress=null,queued=false,previewQueued=false,percent=null,error="已停止，可重新重建") else p})}}
            finally {drain()}
        }
    }
    private fun startPreview() {
        if(!_state.value.cameraRequested || _state.value.savingPhoto || previewJob?.isActive==true)return
        val source=camera ?: return
        previewJob=viewModelScope.launch {
            try {
                while(isActive) {
                    // Warm the first real frame while the gallery is still visible.
                    val frame=withTimeout(8000){source.nextFrame()}
                    _state.update{it.copy(cameraFrame=frame,cameraMessage="对准场景，按下快门")}
                    delay(200)
                }
            } catch(t:TimeoutCancellationException){
                _state.update{it.copy(cameraRequested=false,cameraClosing=it.cameraStageAttached,cameraFrame=null,message="相机准备超时，请重试",cameraMessage="相机准备超时")}
            } catch(c:CancellationException){throw c}
            catch(f:Exception){_state.update{it.copy(cameraRequested=false,cameraClosing=it.cameraStageAttached,cameraFrame=null,message="相机不可用：${f.message}",cameraMessage="相机不可用：${f.message}")}}
        }
    }
    private fun capture(frame:CameraFrame?=null) {
        if(_state.value.savingPhoto || _state.value.loading || _state.value.deletingIds.isNotEmpty())return
        _state.update{it.copy(savingPhoto=true,busy=true,message="正在保存照片…")}
        viewModelScope.launch {
            var captured:CapturePhoto?=null
            try {
                if(frame!=null) {
                    previewJob?.cancelAndJoin();previewJob=null
                    checkNotNull(camera).selectFrame(frame)
                }
                val photo=generate.capturePhoto();captured=photo
                _state.update{it.copy(cameraRequested=false,cameraClosing=it.cameraStageAttached,cameraFrame=null,selectedId=null,viewing3D=false,
                    photos=listOf(PhotoItem(photo,progress="等待重建",queued=true,queuedAt=System.currentTimeMillis()))+it.photos.filterNot{p->p.id==photo.path},message="")}
                generate.markQueued(photo)
                queue.addLast(photo)
                drain()
            } catch(c:CancellationException){throw c}
            catch(f:Exception){
                captured?.let{photo->updatePhoto(photo){it.copy(progress=null,queued=false,error="加入队列失败：${f.message}")}}
                _state.update{it.copy(message=if(captured==null)"拍照失败：${f.message}" else "",cameraMessage="拍照失败：${f.message}")}
            } finally {
                _state.update{it.copy(savingPhoto=false,busy=it.activeId!=null || queue.isNotEmpty() || previewQueue.isNotEmpty())}
                if(_state.value.cameraRequested)startPreview()
            }
        }
    }
    private fun drain() {
        if(worker?.isCompleted==false || (queue.isEmpty() && previewQueue.isEmpty()))return
        worker=viewModelScope.launch {
            try {
                while(queue.isNotEmpty() || previewQueue.isNotEmpty()) {
                    if(queue.isEmpty()) {
                        val item=previewQueue.removeFirst()
                        if(_state.value.photos.none{it.id==item.id} || item.id in _state.value.deletingIds)continue
                        _state.update{state->state.copy(activeId=item.id,busy=true,photos=state.photos.map{photo->
                            if(photo.id==item.id)photo.copy(previewQueued=false,progress="正在准备 3D 浏览…",percent=98) else photo
                        })}
                        try {
                            generate.preparePreview(item.photo,checkNotNull(item.scene))
                            updatePhoto(item.photo){it.copy(progress=null,percent=100,error=null)}
                        } catch(c:CancellationException){throw c}
                        catch(f:Exception){updatePhoto(item.photo){it.copy(progress=null,previewQueued=false,percent=null,error="3D 浏览准备失败：${f.message}")}}
                        continue
                    }
                    val photo=queue.removeFirst()
                    _state.update{it.copy(activeId=photo.path,busy=true)}
                    updatePhoto(photo){it.copy(queued=false,progress="准备重建",percent=1,error=null)}
                    val duration=estimatedMs.coerceAtLeast(10_000L)
                    val ticker=launch {
                        var ticks=0L
                        while(isActive) {
                            delay(500);ticks+=500
                            val percent=(1+90.0*ticks/duration).toInt().coerceAtMost(95)
                            updatePhoto(photo){it.copy(percent=maxOf(it.percent ?: 1,percent))}
                        }
                    }
                    try {
                        val result=generate.reconstruct(photo){stage->updatePhoto(photo){it.copy(progress=stage,percent=maxOf(it.percent ?: 1,when {stage.contains("Gaussian")->97;stage.contains("准备 3D")->98;stage.contains("保存空间")->99;else->1}))}}
                        estimatedMs=result.generationMs.coerceAtLeast(10_000L)
                        updatePhoto(photo){it.copy(scene=result,progress=null,percent=100,error=null,queued=false)}
                    } catch(c:CancellationException){throw c}
                    catch(f:Exception){updatePhoto(photo){it.copy(progress=null,percent=null,error="重建失败：${f.message}",queued=false)}}
                    finally {ticker.cancelAndJoin()}
                }
            } finally {_state.update{it.copy(activeId=null,busy=it.savingPhoto)}}
        }
    }
    private fun updatePhoto(photo:CapturePhoto,change:(PhotoItem)->PhotoItem) {
        _state.update{it.copy(photos=it.photos.map{item->if(item.id==photo.path)change(item) else item})}
    }
}
