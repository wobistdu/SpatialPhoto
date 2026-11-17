package com.example.spatialphoto.ui.capture.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.layout.depth
import com.example.spatialphoto.domain.model.CapturePhoto
import com.example.spatialphoto.domain.usecase.WindowPhotoGeometry
import com.example.spatialphoto.ui.capture.CaptureDiagnostics
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.min
import java.io.File

// Owner-local SDK units are metres; preserve the baseline inset before moving the companion.
private const val PHOTO_FRONT_GAP = .012f

/** Native resources belong to exactly one visible photo, including cancellation paths. */
private class NativePhotoScene : AutoCloseable {
    var owner: Entity? = null
    var model: Entity? = null
    var resource: GaussianSplattingResource? = null
    var hitTarget:Entity?=null
    var hitShape:ShapeResource?=null
    var hitPhysics:PhysicsMaterialResource?=null
    var sizes: Pair<IntSize, IntSize>? = null
    var counted = false
    var closed = false
        private set

    override fun close() {
        if (closed) return
        closed = true
        owner?.takeIf { it.valid }?.destroy()
        owner = null
        model = null
        hitTarget=null
        hitShape?.close();hitShape=null
        hitPhysics?.close();hitPhysics=null
        resource?.close(); resource = null
        if (counted) {
            counted = false
            CaptureDiagnostics.previewLiveCount--
        }
    }
}

/** Native main-scene rendering supplies both actual eyes and physical head parallax.
 * No opaque angle mask: side views remain visible inside the bounded window.
 */
@Composable internal fun WindowGaussianPreview(
    path: String,
    photo: CapturePhoto,
    windowSize: IntSize,
    onAttached: () -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSwipe:(Int)->Unit={},
    presentationScale:Float=1f,
) = key(path) {
    val scene = remember { NativePhotoScene() }
    val context=LocalContext.current
    val browse by rememberUpdatedState(onSwipe)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val attached by rememberUpdatedState(onAttached)
    val failed by rememberUpdatedState(onFailed)
    DisposableEffect(Unit) {
        onDispose {
            scene.close()
            if (CaptureDiagnostics.attachedPath == path) {
                CaptureDiagnostics.attachedPath = null
                CaptureDiagnostics.fitVerified = false
            }
        }
    }
    SpatialView(
        modifier = modifier.onSizeChanged { viewSize = it }.depth(640.dp).pointerInput(path) {
            var horizontal=0f
            detectSpatialDragGesture(context,targetedToEntity=TargetEntity.any(),
                onDragStart={_,_->horizontal=0f},
                onDragEnd={if(abs(horizontal)>72.dp.toPx())browse(if(horizontal<0)1 else -1)},
                onDragCancel={horizontal=0f}) { value->horizontal+=value.dragAmount.x }
        },
        initial = initial@{ content, _ ->
            // Keep a strong pending reference across withContext's cancellable dispatch back.
            var pending: GaussianSplattingResource? = null
            try {
                val job = currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    val refined = com.example.spatialphoto.data.repository.PhotoColorRefinement.prepare(File(path),photo) { job.ensureActive() }
                    val derived = WindowPhotoGeometry.prepare(refined, photo) { job.ensureActive() }
                    job.ensureActive()
                    pending = GaussianSplattingResource(derived.absolutePath, LoadType.FROM_STORAGE)
                }
                job.ensureActive()
                if (scene.closed) return@initial
                scene.resource = pending
                pending = null
                val owner = Entity().also { scene.owner = it; content.addEntity(it) }
                val model = Entity().also { scene.model = it; owner.addChild(it) }
                model.components[GaussianSplattingComponent::class.java] = GaussianSplattingComponent().apply {
                    gaussianSplattingResource = scene.resource
                    enablePerformanceMode = false
                }
                scene.hitPhysics=PhysicsMaterialResource()
                scene.hitTarget=Entity().also { target->
                    owner.addChild(target)
                    target.components[InteractableComponent::class.java]=InteractableComponent()
                }
                scene.counted = true
                CaptureDiagnostics.previewLiveCount++
                android.util.Log.i("SpatialPhoto", "NATIVE_WINDOW_GAUSSIAN_READY path=$path sourceUnchanged=true noRenderTexture=true")
            } catch (cancelled: CancellationException) {
                scene.close()
                throw cancelled
            } catch (error: Exception) {
                scene.close()
                failed(error.message ?: "3D照片加载失败")
            } finally {
                pending?.close()
            }
        },
        update = update@{ content, _ ->
            val model = scene.model ?: return@update
            if (windowSize == IntSize.Zero || viewSize == IntSize.Zero || scene.sizes == (windowSize to viewSize)) return@update
            try {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                val half = Vector3(abs(corner.x), abs(corner.y), abs(corner.z))
                require(presentationScale>=1f && presentationScale.isFinite())
                val baselineHalf=Vector3(half.x/presentationScale,half.y/presentationScale,half.z)
                val aspect = photo.width.toFloat() / photo.height
                // Reserve room for the largest perspective expansion, without fitting the source world box.
                val imageFit = (min(baselineHalf.x * 2, baselineHalf.y * 2 * aspect) * .90f /
                    (1 + WindowPhotoGeometry.HALF_DEPTH / WindowPhotoGeometry.REFERENCE_DISTANCE)).toFloat()
                // Planar containers have a fixed 640dp depth even when their width grows.
                // The separate companion is already recessed. Fit its full depth volume
                // so increasing the image size is not silently cancelled by a half-volume clamp.
                val depthFit=((half.z-PHOTO_FRONT_GAP-.02f)/(2*(WindowPhotoGeometry.HALF_DEPTH+.025))).toFloat()
                var width=min(imageFit,depthFit)
                val halfDepth = (width * WindowPhotoGeometry.HALF_DEPTH).toFloat()
                require(width.isFinite() && width > 0 && halfDepth * 2 + .02f < half.z * 2) { "窗口深度不足" }
                model.components[TransformComponent::class.java]!!.apply {
                    setScaleVector(Vector3(width))
                    setPosition(Vector3.ZERO)
                }
                val initialBounds = model.getVisualBounds(scene.owner!!)
                val initialPadding = initialBounds.halfExtent + Vector3(width * .025f)
                val fit=minOf(1f,baselineHalf.x/(abs(initialBounds.center.x)+initialPadding.x),
                    baselineHalf.y/(abs(initialBounds.center.y)+initialPadding.y),
                    (half.z-PHOTO_FRONT_GAP)/(presentationScale*initialPadding.z))*.98f
                val baselineWidth=width*fit
                width=baselineWidth*presentationScale
                model.components[TransformComponent::class.java]!!.apply {
                    setScaleVector(Vector3(width))
                    // Preserve the source camera's horizontal image centre; an asymmetric
                    // Gaussian bounds centre is not the photographic centre. The companion applies
                    // the additional distance without moving the foreground controls.
                    setPosition(Vector3(0f,0f,-initialBounds.center.z*fit*presentationScale))
                }
                val bounds = model.getVisualBounds(scene.owner!!)
                val paddedHalf = bounds.halfExtent + Vector3(width * .025f)
                require(abs(bounds.center.x) + paddedHalf.x < half.x &&
                    abs(bounds.center.y) + paddedHalf.y < half.y &&
                    abs(bounds.center.z) + paddedHalf.z < half.z) { "照片超出窗口边界" }
                val frontEdge=bounds.center.z+paddedHalf.z
                val rearEdge=bounds.center.z-paddedHalf.z
                require(frontEdge<half.z-PHOTO_FRONT_GAP && rearEdge> -half.z) { "照片后置空间不足" }
                val globalCenter=content.convertPosition(bounds.center,content.localSpatialCoordinateSpace,ViewCoordinateSpace.Global)
                val shape=ShapeResource.createBox(bounds.halfExtent*2f)
                val oldShape=scene.hitShape;scene.hitShape=shape
                scene.hitTarget!!.components[CollisionComponent::class.java]=CollisionComponent(collisionShape=listOf(shape),physicsMaterial=scene.hitPhysics!!)
                scene.hitTarget!!.components[TransformComponent::class.java]!!.setPosition(bounds.center)
                oldShape?.close()
                scene.sizes = windowSize to viewSize
                CaptureDiagnostics.attachedPath = path
                CaptureDiagnostics.fitVerified = true
                android.util.Log.i("SpatialPhoto", "NATIVE_WINDOW_PHOTO width=$width bounds=$bounds regionHalf=$half paddedHalf=$paddedHalf frontEdge=$frontEdge rearEdge=$rearEdge baselineWidth=$baselineWidth presentationScale=$presentationScale globalCenterPx=$globalCenter recessed=true stereo=SYSTEM_NATIVE")
                attached()
            } catch (error: Exception) {
                scene.close()
                CaptureDiagnostics.attachedPath = null
                CaptureDiagnostics.fitVerified = false
                android.util.Log.e("SpatialPhoto","NATIVE_PHOTO_LAYOUT_FAILED",error)
                failed(error.message ?: "3D照片布局失败")
            }
        },
    )
}

