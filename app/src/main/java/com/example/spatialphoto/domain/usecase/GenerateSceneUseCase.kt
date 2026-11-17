package com.example.spatialphoto.domain.usecase

import com.example.spatialphoto.data.repository.*
import com.example.spatialphoto.domain.model.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface SceneGenerator {
    suspend fun capturePhoto():CapturePhoto
    suspend fun markQueued(photo:CapturePhoto) {}
    suspend fun preparePreview(photo:CapturePhoto,scene:GeneratedScene) {}
    suspend fun listPhotos():List<PhotoItem>
    suspend fun deletePhoto(photo:CapturePhoto):String?
    suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit):GeneratedScene
    suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit):GeneratedScene
}
class GenerateSceneUseCase(
    private val camera:PhotoRepository,
    private val sharp:SharpRepository,
    private val library:PhotoLibraryRepository,
    private val clock:()->Long={android.os.SystemClock.elapsedRealtime()},
):SceneGenerator {
    override suspend fun capturePhoto()=camera.capture()
    override suspend fun markQueued(photo:CapturePhoto)=library.markQueued(photo)
    override suspend fun listPhotos()=library.list()
    override suspend fun deletePhoto(photo:CapturePhoto)=library.delete(photo)
    override suspend fun generate(progress:(String)->Unit,onPhoto:(CapturePhoto)->Unit):GeneratedScene {
        progress("正在拍照…")
        val photo=camera.capture()
        onPhoto(photo) // Persisted actual photo is visible before loading/inference starts.
        return reconstruct(photo,progress)
    }
    override suspend fun preparePreview(photo:CapturePhoto,scene:GeneratedScene) {
        val started=clock()
        withContext(Dispatchers.IO) {
            val job=currentCoroutineContext()
            val refined=PhotoColorRefinement.prepare(File(scene.path),photo){job.ensureActive()}
            WindowPhotoGeometry.prepare(refined,photo){job.ensureActive()}
            android.util.Log.i("SpatialPhoto","PREVIEW_PREPARED ms=${clock()-started} source=${scene.path}")
        }
    }
    override suspend fun reconstruct(photo:CapturePhoto,progress:(String)->Unit):GeneratedScene {
        val began=clock()
        try {
            val result=sharp.predict(photo,progress)
            val encoded=withContext(Dispatchers.Default) {
                progress("校验并编码 Gaussian…")
                val file=File(File(photo.path).parentFile,"scene.ply")
                GeneratedScene(file.absolutePath,GaussianPlyUseCase.write(result,photo,file),clock()-began,result.inputResolution)
            }
            // Ready means the actual first-open assets are already on disk, not only the model output.
            progress("正在准备 3D 浏览…")
            preparePreview(photo,encoded)
            val scene=encoded.copy(generationMs=clock()-began)
            progress("正在保存空间照片…")
            library.saveScene(photo,scene)
            android.util.Log.i("SpatialPhoto","SCENE_GENERATED count=${scene.count} ms=${scene.generationMs} freshCapture=true path=${scene.path}")
            return scene
        } catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){
            runCatching { library.saveFailure(photo,failure.message ?: "重建失败") }
            throw failure
        }
    }
}
