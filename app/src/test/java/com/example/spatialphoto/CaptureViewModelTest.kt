package com.example.spatialphoto

import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.SceneGenerator
import com.example.spatialphoto.ui.capture.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.*

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private val photo=CapturePhoto("/captures/photo-1/photo.png",100,100,50f,50f,50f,50f)
    private val scene=GeneratedScene("/captures/photo-1/scene.ply",123,50)
    private inner class Fake:SceneGenerator {
        var calls=0;var retries=0;var fail=false;var deleteFail=false;var deletes=0
        var list=emptyList<PhotoItem>()
        override suspend fun deletePhoto(photo:CapturePhoto):String?{deletes++;delay(100);if(deleteFail)error("denied");list=list.filterNot{it.id==photo.path};return null}
        override suspend fun listPhotos()=list
        override suspend fun capturePhoto():CapturePhoto {calls++;return photo}
        override suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit):GeneratedScene {
            calls++;onPhoto(photo);progress("推理中");delay(100)
            if(fail)error("failed")
            return scene
        }
        override suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit):GeneratedScene {retries++;progress("推理中");delay(100);if(fail)error("failed");return scene}
    }
    @Before fun setup(){Dispatchers.setMain(StandardTestDispatcher())}
    @After fun teardown(){Dispatchers.resetMain()}
    @Test fun initialLoadsHistory()=runTest{val fake=Fake().apply{list=listOf(PhotoItem(photo,scene))};val vm=CaptureViewModel(fake);advanceUntilIdle();assertEquals(1,vm.state.value.photos.size);assertFalse(vm.state.value.loading);assertNull(vm.state.value.selected)}
    @Test fun photoVisibleBeforeReconstructionFinishes()=runTest{val vm=CaptureViewModel(Fake());advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);runCurrent();assertTrue(vm.state.value.busy);assertEquals(photo,vm.state.value.photos.single().photo);assertEquals("推理中",vm.state.value.photos.single().progress);assertNull(vm.state.value.selected)}
    @Test fun completionStaysInList()=runTest{val vm=CaptureViewModel(Fake());advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);advanceUntilIdle();assertTrue(vm.state.value.photos.single().ready);assertNull(vm.state.value.scene);assertNull(vm.state.value.selectedId);assertFalse(vm.state.value.busy)}
    @Test fun repeatedCaptureIsIgnored()=runTest{val fake=Fake();val vm=CaptureViewModel(fake);advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);vm.onEvent(CaptureEvent.Capture);advanceUntilIdle();assertEquals(1,fake.calls)}
    @Test fun incompletePhotoOpens2DButCannotEnter3D()=runTest{val vm=CaptureViewModel(Fake());advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);runCurrent();vm.onEvent(CaptureEvent.Open(photo.path));assertNotNull(vm.state.value.selected);vm.onEvent(CaptureEvent.Enter3D);assertFalse(vm.state.value.viewing3D)}
    @Test fun failureRetainsPhoto()=runTest{val vm=CaptureViewModel(Fake().apply{fail=true});advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);advanceUntilIdle();assertEquals(photo,vm.state.value.photos.single().photo);assertNotNull(vm.state.value.photos.single().error);assertFalse(vm.state.value.busy)}
    @Test fun openBackDoesNotRegenerate()=runTest{val fake=Fake();val vm=CaptureViewModel(fake);advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);advanceUntilIdle();vm.onEvent(CaptureEvent.Open(photo.path));assertEquals(scene,vm.state.value.scene);vm.onEvent(CaptureEvent.Back);vm.onEvent(CaptureEvent.Back);assertNull(vm.state.value.scene);assertEquals(1,vm.state.value.photos.size);assertEquals(1,fake.calls)}
    @Test fun retryUsesSamePhoto()=runTest{val fake=Fake().apply{list=listOf(PhotoItem(photo,error="interrupted"))};val vm=CaptureViewModel(fake);advanceUntilIdle();vm.onEvent(CaptureEvent.Retry(photo.path));advanceUntilIdle();assertEquals(0,fake.calls);assertEquals(1,fake.retries);assertTrue(vm.state.value.photos.single().ready)}
    @Test fun unknownIdDoesNotSelect()=runTest{val vm=CaptureViewModel(Fake());advanceUntilIdle();vm.onEvent(CaptureEvent.Open("unknown"));assertNull(vm.state.value.selected)}
    @Test fun deleteRemovesOnlyTargetAndClosesSelection()=runTest {
        val other=photo.copy(path="/captures/photo-2/photo.png")
        val fake=Fake().apply{list=listOf(PhotoItem(photo,scene),PhotoItem(other,scene))}
        val vm=CaptureViewModel(fake);advanceUntilIdle();vm.onEvent(CaptureEvent.Open(photo.path));vm.onEvent(CaptureEvent.Delete(photo.path))
        assertNull(vm.state.value.selected);advanceUntilIdle();assertEquals(listOf(other.path),vm.state.value.photos.map{it.id});assertEquals(1,fake.deletes)
    }
    @Test fun deleteFailureKeepsPhoto()=runTest {
        val fake=Fake().apply{list=listOf(PhotoItem(photo,scene));deleteFail=true};val vm=CaptureViewModel(fake)
        advanceUntilIdle();vm.onEvent(CaptureEvent.Delete(photo.path));advanceUntilIdle();assertEquals(1,vm.state.value.photos.size);assertTrue(vm.state.value.message.startsWith("删除失败"));assertTrue(vm.state.value.deletingIds.isEmpty())
    }
    @Test fun deletingItemCannotOpenOrRepeatDeleteOrCapture()=runTest {
        val fake=Fake().apply{list=listOf(PhotoItem(photo,scene))};val vm=CaptureViewModel(fake);advanceUntilIdle()
        vm.onEvent(CaptureEvent.Delete(photo.path));vm.onEvent(CaptureEvent.Open(photo.path));vm.onEvent(CaptureEvent.Delete(photo.path));vm.onEvent(CaptureEvent.Capture)
        assertNull(vm.state.value.selected);advanceUntilIdle();assertEquals(1,fake.deletes);assertEquals(0,fake.calls)
    }
    @Test fun activeReconstructionCanBeCancelledAndDeleted()=runTest {
        val fake=Fake();val vm=CaptureViewModel(fake);advanceUntilIdle();vm.onEvent(CaptureEvent.Capture);runCurrent();vm.onEvent(CaptureEvent.Delete(photo.path));advanceUntilIdle();assertEquals(1,fake.deletes);assertTrue(vm.state.value.photos.isEmpty());assertFalse(vm.state.value.busy)
    }
    @Test fun unknownDeleteIgnored()=runTest{val fake=Fake();val vm=CaptureViewModel(fake);advanceUntilIdle();vm.onEvent(CaptureEvent.Delete("unknown"));advanceUntilIdle();assertEquals(0,fake.deletes)}

    @Test fun openingCameraDoesNotCapture()=runTest {
        val fake=Fake();val vm=CaptureViewModel(fake);advanceUntilIdle()
        vm.onEvent(CaptureEvent.OpenCamera);runCurrent()
        assertTrue(vm.state.value.cameraRequested);assertEquals(0,fake.calls);assertTrue(vm.state.value.photos.isEmpty())
        vm.onEvent(CaptureEvent.CloseCamera);assertFalse(vm.state.value.cameraRequested)
    }
    @Test fun shutterWithoutPreviewDoesNotCapture()=runTest {
        val fake=Fake();val vm=CaptureViewModel(fake);advanceUntilIdle()
        vm.onEvent(CaptureEvent.OpenCamera);vm.onEvent(CaptureEvent.Shutter);advanceUntilIdle()
        assertEquals(0,fake.calls);assertFalse(vm.state.value.busy)
    }
    @Test fun canOpenCameraDuringReconstruction()=runTest {
        val vm=CaptureViewModel(Fake());advanceUntilIdle();vm.onEvent(CaptureEvent.Capture)
        runCurrent();vm.onEvent(CaptureEvent.OpenCamera);assertTrue(vm.state.value.cameraRequested);advanceUntilIdle()
    }
    @Test fun stageFailureReturnsToGalleryWithReason()=runTest {
        val vm=CaptureViewModel(Fake());advanceUntilIdle();vm.onEvent(CaptureEvent.OpenCamera)
        vm.onEvent(CaptureEvent.CameraFailed("Stage unavailable"));assertFalse(vm.state.value.cameraRequested)
        assertEquals("Stage unavailable",vm.state.value.message)
    }
    @Test fun queuedCapturesAreFifoAndFailureDoesNotBlockNext()=runTest {
        var captureCount=0;var active=0;var maxActive=0
        val started=mutableListOf<String>()
        val fake=object:SceneGenerator {
            override suspend fun listPhotos()=emptyList<PhotoItem>()
            override suspend fun capturePhoto()=photo.copy(path="/captures/photo-${++captureCount}/photo.png")
            override suspend fun deletePhoto(photo:CapturePhoto):String?=null
            override suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit):GeneratedScene=error("legacy path used")
            override suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit):GeneratedScene {
                started+=photo.path;active++;maxActive=maxOf(active,maxActive)
                try {delay(3000);if(started.size==2)error("one failure");return scene}
                finally {active--}
            }
        }
        val vm=CaptureViewModel(fake);advanceUntilIdle()
        repeat(3){vm.onEvent(CaptureEvent.Capture);runCurrent()}
        assertEquals(3,vm.state.value.photos.size)
        assertEquals(2,vm.state.value.photos.count{it.queued})
        val first=vm.state.value.photos.last()
        advanceTimeBy(1000);runCurrent()
        assertTrue(vm.state.value.photos.last().percent!! in 1..95)
        vm.onEvent(CaptureEvent.Open(first.id));assertNotNull(vm.state.value.selected)
        advanceUntilIdle()
        assertEquals(listOf(1,2,3).map{"/captures/photo-$it/photo.png"},started)
        assertEquals(1,maxActive);assertEquals(2,vm.state.value.photos.count{it.ready})
        assertEquals(1,vm.state.value.photos.count{it.error!=null});assertFalse(vm.state.value.busy)
        assertEquals(first.id,vm.state.value.selectedId)
    }
    @Test fun readyPhotoOpens3DThenBackTo2D()=runTest {
        val vm=CaptureViewModel(Fake().apply{list=listOf(PhotoItem(photo,scene))});advanceUntilIdle()
        vm.onEvent(CaptureEvent.Open(photo.path));assertTrue(vm.state.value.viewing3D)
        vm.onEvent(CaptureEvent.Enter3D);assertTrue(vm.state.value.viewing3D)
        vm.onEvent(CaptureEvent.Back);assertFalse(vm.state.value.viewing3D);assertEquals(photo.path,vm.state.value.selectedId)
        vm.onEvent(CaptureEvent.Back);assertNull(vm.state.value.selectedId)
    }
    @Test fun repeatedRetryDoesNotDuplicateQueuedWork()=runTest {
        val fake=Fake().apply{list=listOf(PhotoItem(photo,error="failed"))};val vm=CaptureViewModel(fake);advanceUntilIdle()
        repeat(3){vm.onEvent(CaptureEvent.Retry(photo.path))};advanceUntilIdle();assertEquals(1,fake.retries)
    }
    @Test fun durableQueueResumesWithoutRecapture()=runTest {
        val fake=Fake().apply{list=listOf(PhotoItem(photo,queued=true,progress="等待重建"))}
        val vm=CaptureViewModel(fake);advanceUntilIdle();assertEquals(0,fake.calls);assertEquals(1,fake.retries);assertTrue(vm.state.value.photos.single().ready)
    }

    @Test fun loadingCanBeCancelledAndResetsOnEveryOpen()=runTest {
        val vm=CaptureViewModel(Fake().apply{list=listOf(PhotoItem(photo,scene))});advanceUntilIdle()
        vm.onEvent(CaptureEvent.Open(photo.path));vm.onEvent(CaptureEvent.Enter3D);assertTrue(vm.state.value.previewLoading)
        vm.onEvent(CaptureEvent.Back);assertFalse(vm.state.value.previewLoading);assertFalse(vm.state.value.viewing3D);assertNotNull(vm.state.value.selected)
        vm.onEvent(CaptureEvent.Enter3D);assertTrue(vm.state.value.previewLoading)
        vm.onEvent(CaptureEvent.RenderFailed("test"));assertFalse(vm.state.value.previewLoading);assertTrue(vm.state.value.previewMessage.contains("加载失败"))
        vm.onEvent(CaptureEvent.Back);vm.onEvent(CaptureEvent.Enter3D);assertTrue(vm.state.value.previewLoading)
        vm.onEvent(CaptureEvent.Rendered);assertFalse(vm.state.value.previewLoading)
    }
    @Test fun backgroundPreparationMustFinishBeforeLegacyPhotoIsReady()=runTest {
        val fake=object:SceneGenerator {
            override suspend fun listPhotos()=listOf(PhotoItem(photo,scene))
            override suspend fun capturePhoto()=photo
            override suspend fun deletePhoto(photo:CapturePhoto):String?=null
            override suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit)=scene
            override suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit)=scene
            override suspend fun preparePreview(photo:CapturePhoto,scene:GeneratedScene){delay(2000)}
        }
        val vm=CaptureViewModel(fake);runCurrent()
        assertFalse(vm.state.value.photos.single().ready)
        vm.onEvent(CaptureEvent.Open(photo.path));vm.onEvent(CaptureEvent.Enter3D);assertFalse(vm.state.value.viewing3D)
        advanceUntilIdle();assertTrue(vm.state.value.photos.single().ready)
        vm.onEvent(CaptureEvent.Enter3D);assertTrue(vm.state.value.viewing3D)
    }
    @Test fun deletingActiveAndQueuedPhotosResumesRemainingFifoWithoutOverlap()=runTest {
        var next=0;var active=0;var maxActive=0;val began=mutableListOf<String>();val deleted=mutableListOf<String>()
        val fake=object:SceneGenerator {
            override suspend fun listPhotos()=emptyList<PhotoItem>()
            override suspend fun capturePhoto()=photo.copy(path="/captures/photo-${++next}/photo.png")
            override suspend fun deletePhoto(photo:CapturePhoto):String? {if(photo.path.endsWith("photo-1/photo.png"))assertEquals(0,active);deleted+=photo.path;return null}
            override suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit)=scene
            override suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit):GeneratedScene {
                began+=photo.path;active++;maxActive=maxOf(maxActive,active)
                try {delay(5000);return scene}finally{active--}
            }
        }
        val vm=CaptureViewModel(fake);advanceUntilIdle()
        repeat(3){vm.onEvent(CaptureEvent.Capture);runCurrent()}
        val ids=vm.state.value.photos.reversed().map{it.id}
        // The first completion is queued but has not run: deletion must not accidentally cancel the next photo.
        advanceTimeBy(5000)
        vm.onEvent(CaptureEvent.Delete(ids[0]));vm.onEvent(CaptureEvent.Delete(ids[1]));advanceUntilIdle()
        assertEquals(setOf(ids[0],ids[1]),deleted.toSet());assertEquals(listOf(ids[0],ids[2]),began)
        assertEquals(1,maxActive);assertEquals(ids[2],vm.state.value.photos.single().id);assertTrue(vm.state.value.photos.single().ready)
    }
    @Test fun closingCameraCannotReopenUntilItsStageIsDisposed()=runTest {
        val vm=CaptureViewModel(Fake());advanceUntilIdle()
        vm.onEvent(CaptureEvent.OpenCamera);val first=vm.state.value.cameraSession
        vm.onEvent(CaptureEvent.CameraReady);vm.onEvent(CaptureEvent.CloseCamera)
        assertTrue(vm.state.value.cameraClosing)
        vm.onEvent(CaptureEvent.OpenCamera)
        assertFalse(vm.state.value.cameraRequested);assertEquals(first,vm.state.value.cameraSession)
        vm.onEvent(CaptureEvent.CameraDisposed(first));vm.onEvent(CaptureEvent.OpenCamera)
        assertTrue(vm.state.value.cameraRequested);assertFalse(vm.state.value.cameraClosing)
        assertEquals(first+1,vm.state.value.cameraSession)
        // A delayed callback from the old Stage must not close the new camera.
        vm.onEvent(CaptureEvent.CameraDisposed(first));assertTrue(vm.state.value.cameraRequested)
    }
    @Test fun repeatedOpenCannotResetCurrentCameraOrStartAnotherSession()=runTest {
        val vm=CaptureViewModel(Fake());advanceUntilIdle()
        vm.onEvent(CaptureEvent.OpenCamera);val session=vm.state.value.cameraSession
        repeat(5){vm.onEvent(CaptureEvent.OpenCamera)}
        assertEquals(session,vm.state.value.cameraSession);assertTrue(vm.state.value.cameraRequested)
    }
    @Test fun cancelWhilePreparingAllowsRetryAndIgnoresOldDisposal()=runTest {
        val vm=CaptureViewModel(Fake());advanceUntilIdle()
        vm.onEvent(CaptureEvent.OpenCamera);val first=vm.state.value.cameraSession
        vm.onEvent(CaptureEvent.CloseCamera);assertFalse(vm.state.value.cameraClosing)
        vm.onEvent(CaptureEvent.OpenCamera);vm.onEvent(CaptureEvent.CameraDisposed(first))
        assertTrue(vm.state.value.cameraRequested)
    }

    @Test fun slowCameraPreparationTimesOutWithoutStoppingReconstruction()=runTest {
        val source=object:com.example.spatialphoto.data.repository.CameraPreviewSource {
            override suspend fun nextFrame():com.example.spatialphoto.data.repository.CameraFrame {delay(20000);error("not reached")}
            override fun selectFrame(frame:com.example.spatialphoto.data.repository.CameraFrame){}
        }
        val vm=CaptureViewModel(Fake(),source);advanceUntilIdle()
        vm.onEvent(CaptureEvent.Capture);runCurrent();vm.onEvent(CaptureEvent.OpenCamera)
        assertTrue(vm.state.value.cameraRequested)
        advanceTimeBy(8100);runCurrent()
        assertFalse(vm.state.value.cameraRequested);assertFalse(vm.state.value.cameraClosing)
        assertTrue(vm.state.value.message.contains("超时"));assertTrue(vm.state.value.photos.single().ready)
    }
    @Test fun cancelledWarmupDoesNotPublishLateFrameOrHoldTheCamera()=runTest {
        var cancelled=false
        val source=object:com.example.spatialphoto.data.repository.CameraPreviewSource {
            override suspend fun nextFrame():com.example.spatialphoto.data.repository.CameraFrame {try{delay(20000);error("not reached")}finally{cancelled=true}}
            override fun selectFrame(frame:com.example.spatialphoto.data.repository.CameraFrame){}
        }
        val vm=CaptureViewModel(Fake(),source);advanceUntilIdle()
        vm.onEvent(CaptureEvent.OpenCamera);runCurrent();vm.onEvent(CaptureEvent.CloseCamera);advanceUntilIdle()
        assertTrue(cancelled);assertFalse(vm.state.value.cameraRequested);assertFalse(vm.state.value.cameraClosing)
    }


    @Test fun browsePreservesModeAndStopsAtEdges()=runTest {
        val photos=(1..3).map{PhotoItem(photo.copy(path="/captures/photo-$it/photo.png"),scene)}
        val vm=CaptureViewModel(Fake().apply{list=photos});advanceUntilIdle()
        vm.onEvent(CaptureEvent.Open(photos.first().id));assertTrue(vm.state.value.viewing3D)
        vm.onEvent(CaptureEvent.Browse(-1));assertEquals(photos.first().id,vm.state.value.selectedId)
        val revision=vm.state.value.previewRevision
        vm.onEvent(CaptureEvent.Browse(1));assertEquals(photos[1].id,vm.state.value.selectedId)
        assertTrue(vm.state.value.viewing3D);assertTrue(vm.state.value.previewLoading);assertTrue(vm.state.value.previewRevision>revision)
        vm.onEvent(CaptureEvent.Back);vm.onEvent(CaptureEvent.Browse(1));assertEquals(photos.last().id,vm.state.value.selectedId);assertFalse(vm.state.value.viewing3D)
        vm.onEvent(CaptureEvent.Browse(1));assertEquals(photos.last().id,vm.state.value.selectedId)
        vm.onEvent(CaptureEvent.Browse(-1));assertEquals(photos[1].id,vm.state.value.selectedId)
    }
    @Test fun browsingUnreadyPhotoFallsBackTo2D()=runTest {
        val incomplete=PhotoItem(photo.copy(path="/other/photo.png"))
        val vm=CaptureViewModel(Fake().apply{list=listOf(PhotoItem(photo,scene),incomplete)});advanceUntilIdle()
        vm.onEvent(CaptureEvent.Open(photo.path));vm.onEvent(CaptureEvent.Browse(1))
        assertEquals(incomplete.id,vm.state.value.selectedId);assertFalse(vm.state.value.viewing3D);assertFalse(vm.state.value.previewLoading)
    }
    @Test fun exportUsesSnapshotBlocksDeletionButAllowsCapture()=runTest {
        var calls=0;var exported=emptyList<PhotoItem>()
        val service=object:com.example.spatialphoto.domain.usecase.PhotoExporter {
            override fun hasDestination()=true
            override suspend fun export(photos:List<PhotoItem>,progress:(Int,Int)->Unit):String {calls++;exported=photos;delay(5000);return "done"}
        }
        val fake=Fake().apply{list=listOf(PhotoItem(photo,scene))}
        val vm=CaptureViewModel(fake,exporter=service);advanceUntilIdle()
        vm.onEvent(CaptureEvent.ExportAll);runCurrent();assertTrue(vm.state.value.exporting)
        vm.onEvent(CaptureEvent.ExportAll);vm.onEvent(CaptureEvent.Delete(photo.path));vm.onEvent(CaptureEvent.Capture);runCurrent()
        assertEquals(0,fake.deletes);assertEquals(1,fake.calls);assertEquals(1,calls);assertEquals(1,exported.size)
        advanceUntilIdle();assertFalse(vm.state.value.exporting);assertEquals("done",vm.state.value.exportMessage)
    }
    @Test fun exportPermissionCancelDoesNotWrite()=runTest {
        val service=object:com.example.spatialphoto.domain.usecase.PhotoExporter {
            override fun hasDestination()=false
            override suspend fun export(photos:List<PhotoItem>,progress:(Int,Int)->Unit):String=error("must not export")
        }
        val vm=CaptureViewModel(Fake().apply{list=listOf(PhotoItem(photo,scene))},exporter=service);advanceUntilIdle()
        vm.onEvent(CaptureEvent.ExportAll);assertTrue(vm.state.value.exportNeedsPermission)
        vm.onEvent(CaptureEvent.ExportPermissionResult(false,"cancel"));assertFalse(vm.state.value.exportNeedsPermission);assertFalse(vm.state.value.exporting)
    }
}
