package com.example.spatialphoto

import com.example.spatialphoto.domain.model.*
import com.example.spatialphoto.domain.usecase.SceneGenerator
import com.example.spatialphoto.ui.capture.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueStatusTest {
    private fun photo(index:Int)=CapturePhoto("/captures/photo-$index/photo.png",100,100,50f,50f,50f,50f)
    private val scene=GeneratedScene("/captures/scene.ply",123,1000)
    private inner class Fake:SceneGenerator {
        var active=0;var maxActive=0;var captures=3;var deleteFails=false
        val started=mutableListOf<String>();val failPreparation=mutableSetOf<String>()
        override suspend fun listPhotos()=(1..3).map{PhotoItem(photo(it),scene)}
        override suspend fun capturePhoto()=photo(++captures)
        override suspend fun deletePhoto(photo:CapturePhoto):String? {if(deleteFails)error("delete failed");return null}
        override suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit):GeneratedScene=error("unused")
        private suspend fun work(label:String) {
            started+=label;active++;maxActive=maxOf(maxActive,active)
            try {delay(1000)}finally{active--}
        }
        override suspend fun preparePreview(photo:CapturePhoto,scene:GeneratedScene) {
            work("preview:${photo.path}")
            if(failPreparation.remove(photo.path))error("prepare failed")
        }
        override suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit):GeneratedScene {
            work("reconstruct:${photo.path}");return scene
        }
    }
    @Before fun setup(){Dispatchers.setMain(StandardTestDispatcher())}
    @After fun teardown(){Dispatchers.resetMain()}

    @Test fun threeExistingPhotosShowOneActiveAndTwoWaiting()=runTest {
        val fake=Fake();val vm=CaptureViewModel(fake);runCurrent()
        assertEquals(1,fake.active);assertEquals(1,vm.state.value.processingCount);assertEquals(2,vm.state.value.waitingCount)
        assertEquals(1,vm.state.value.photos.count{it.percent!=null})
        assertTrue(vm.state.value.photos.drop(1).all{it.previewQueued && it.progress==null && !it.ready})
        advanceTimeBy(1000);runCurrent()
        assertEquals(1,vm.state.value.processingCount);assertEquals(1,vm.state.value.waitingCount)
        assertTrue(vm.state.value.photos.first().ready)
        advanceUntilIdle()
        assertEquals(1,fake.maxActive);assertEquals(0,vm.state.value.processingCount);assertEquals(0,vm.state.value.waitingCount)
        assertTrue(vm.state.value.photos.all{it.ready});assertFalse(vm.state.value.busy)
    }
    @Test fun capturesAndPreviewPreparationShareOneWorker()=runTest {
        val fake=Fake();val vm=CaptureViewModel(fake);runCurrent()
        repeat(3){vm.onEvent(CaptureEvent.Capture);runCurrent()}
        assertEquals(1,fake.active);assertEquals(1,vm.state.value.processingCount);assertEquals(5,vm.state.value.waitingCount)
        assertEquals(3,vm.state.value.photos.count{it.queued});assertEquals(2,vm.state.value.photos.count{it.previewQueued})
        assertTrue(vm.state.value.photos.filter{it.waiting}.all{it.percent==null})
        advanceTimeBy(1000);runCurrent()
        assertEquals(photo(4).path,vm.state.value.activeId);assertEquals(1,vm.state.value.processingCount)
        advanceUntilIdle()
        assertEquals(listOf("preview:${photo(1).path}")+ (4..6).map{"reconstruct:${photo(it).path}"} + (2..3).map{"preview:${photo(it).path}"},fake.started)
        assertEquals(1,fake.maxActive);assertEquals(6,vm.state.value.photos.count{it.ready})
    }
    @Test fun repeatedPreviewRetryNeverQueuesReconstruction()=runTest {
        val fake=Fake().apply{failPreparation+=photo(1).path};val vm=CaptureViewModel(fake);advanceUntilIdle()
        assertNotNull(vm.state.value.photos.first().error)
        repeat(3){vm.onEvent(CaptureEvent.Retry(photo(1).path))}
        assertTrue(vm.state.value.photos.first().previewQueued);assertNull(vm.state.value.photos.first().percent)
        advanceUntilIdle()
        assertEquals(2,fake.started.count{it=="preview:${photo(1).path}"})
        assertTrue(fake.started.none{it.startsWith("reconstruct:")});assertTrue(vm.state.value.photos.first().ready)
    }
    @Test fun failedDeleteOfWaitingPreviewDoesNotLeavePhantomQueue()=runTest {
        val fake=Fake().apply{deleteFails=true};val vm=CaptureViewModel(fake);runCurrent()
        vm.onEvent(CaptureEvent.Delete(photo(2).path));runCurrent()
        val failed=vm.state.value.photos.first{it.id==photo(2).path}
        assertFalse(failed.waiting);assertFalse(failed.processing);assertNotNull(failed.error)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy);assertEquals(0,vm.state.value.waitingCount)
        assertFalse(fake.started.contains("preview:${photo(2).path}"))
    }
}
