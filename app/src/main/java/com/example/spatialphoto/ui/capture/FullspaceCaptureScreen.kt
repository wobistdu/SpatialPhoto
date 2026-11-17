package com.example.spatialphoto.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import com.example.spatialphoto.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.spatialphoto.platform.SpatialApplication
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.example.spatialphoto.domain.usecase.PhotoFraming
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.setAccessibilityActions
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.pico.spatial.core.container.SpaceState
import com.pico.spatial.core.container.getSelfSpaceState

/** Mixed Full Space is used only while composing a photograph, never for viewing it. */
@Composable
fun FullspaceCaptureScreen() {
    val context=LocalContext.current
    val app=context.applicationContext as com.example.spatialphoto.platform.SpatialApplication
    val vm:CaptureViewModel=viewModel(viewModelStoreOwner=app,factory=app.captureViewModelFactory)
    val state by vm.state.collectAsState()
    val navigator=LocalSpatialNavigator.current
    val session=remember{state.cameraSession}
    var exitAttempt by remember{mutableIntStateOf(0)}
    val initialExitError: String? = null
    var exitError by remember{mutableStateOf(initialExitError)}
    var showAttachments by remember{mutableStateOf(true)}
    val anchor=remember{Entity()}
    val ownedAttachments: MutableList<Entity> = remember { mutableListOf() }
    var now by remember{mutableLongStateOf(android.os.SystemClock.elapsedRealtime())}
    LaunchedEffect(Unit){while(true){now=android.os.SystemClock.elapsedRealtime();delay(250)}}
    DisposableEffect(vm){
        CaptureDiagnostics.captureStageActive=true
        onDispose{
            vm.onEvent(CaptureEvent.CameraDisposed(session))
            CaptureDiagnostics.captureStageActive=false
            CaptureDiagnostics.viewfinderSize=androidx.compose.ui.unit.IntSize.Zero
            ownedAttachments.forEach { child ->
                if(anchor.valid && child.valid)anchor.removeChild(child)
            }
            anchor.takeIf{it.valid}?.destroy()
        }
    }
    LaunchedEffect(Unit){
        try {
            check(withTimeoutOrNull(5000) {
                while (context.getSelfSpaceState() != SpaceState.FULL_SPACE) {
                    delay(50)
                }
                true
            } == true) { "进入 Fullspace 超时，请重试" }
            check(navigator.minimizeWindowContainer("PhotoGallery")){"相册窗口未能收起"}
            vm.onEvent(CaptureEvent.CameraReady)
        } catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){vm.onEvent(CaptureEvent.CameraFailed("无法准备取景页：${failure.message}"))}
    }
    LaunchedEffect(state.cameraRequested,exitAttempt){
        if(!state.cameraRequested){
            exitError=null
            // Restore first. Never rely indefinitely on frames from an empty/minimized Stage.
            navigator.restoreWindowContainer("PhotoGallery")
            showAttachments=false
            awaitCameraSurfaceDetach()
            try {
                kotlinx.coroutines.withTimeout(5000){navigator.closeStage()}
            } catch(t:TimeoutCancellationException) {
                showAttachments=true;exitError="返回超时，请再次返回相册"
            } catch(c:CancellationException){throw c}
            catch(f:Exception){showAttachments=true;exitError="暂时无法返回，请重试"}
        }
    }
    if(showAttachments) SpatialView(

        modifier=Modifier.size(1.dp),
        initial={content,attachments ->
            anchor.components[AnchorComponent::class.java]=AnchorComponent()
            val viewfinder=checkNotNull(attachments.entity("camera-viewfinder"))
            viewfinder.components[AccessibilityComponent::class.java]=AccessibilityComponent(
                label="相机实时画面",value="60 度横向取景"
            ).apply{isAccessibilityElement=true}
            content.setAccessibilityActions(viewfinder,"capture.image"){}
            ownedAttachments.add(viewfinder)
            viewfinder.components[TransformComponent::class.java]!!.apply {
                setPosition(Vector3(0f,0f,-PhotoFraming.VIEWFINDER_DISTANCE))
                setScaleVector(Vector3.ONE)
            }
            anchor.addChild(viewfinder)
            val frameLayer=checkNotNull(attachments.entity("camera-frame"))
            frameLayer.components[AccessibilityComponent::class.java]=AccessibilityComponent(
                label="有效拍摄范围",value="四角内的画面将保存"
            ).apply{isAccessibilityElement=true}
            content.setAccessibilityActions(frameLayer,"capture.frame"){}
            ownedAttachments.add(frameLayer)
            frameLayer.components[TransformComponent::class.java]!!.apply {
                setPosition(Vector3(0f,0f,-PhotoFraming.VIEWFINDER_DISTANCE+0.02f))
                setScaleVector(Vector3.ONE*((PhotoFraming.VIEWFINDER_DISTANCE-0.02f)/PhotoFraming.VIEWFINDER_DISTANCE))
            }
            anchor.addChild(frameLayer)
            val panel=checkNotNull(attachments.entity("camera-controls"))
            ownedAttachments.add(panel)
            val halfImageHeight=PhotoFraming.VIEWFINDER_LAYOUT_WIDTH_DP/1440f/(PhotoFraming.ASPECT.toFloat()*2)
            panel.components[TransformComponent::class.java]!!.setPosition(Vector3(0f,-halfImageHeight+0.025f,-PhotoFraming.VIEWFINDER_DISTANCE+0.02f))
            panel.components[AccessibilityComponent::class.java]=AccessibilityComponent(
                label="拍摄取景器",value="Fullspace 实时取景"
            ).apply{isAccessibilityElement=true}
            content.setAccessibilityActions(panel,"capture.viewfinder"){}
            anchor.addChild(panel)
            content.addEntity(anchor)
        },
        attachments={
            AttachmentPanel("camera-viewfinder") {
                Box(Modifier.width(PhotoFraming.VIEWFINDER_LAYOUT_WIDTH_DP.dp).aspectRatio(PhotoFraming.ASPECT.toFloat()).onSizeChanged{CaptureDiagnostics.viewfinderSize=it},contentAlignment=Alignment.Center){
                    val frame=state.cameraFrame
                    if(frame!=null)Image(remember(frame){frame.bitmap.asImageBitmap()},"60 度横向取景，框内画面将完整保存",
                        Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    else CircularProgressIndicator()

                }
            }
            // Frame is its own front surface so the native camera image cannot overdraw it.
            AttachmentPanel("camera-frame") {
                ViewfinderCorners(Modifier.width(PhotoFraming.VIEWFINDER_LAYOUT_WIDTH_DP.dp).aspectRatio(PhotoFraming.ASPECT.toFloat()))
            }
            AttachmentPanel("camera-controls"){
                Column(Modifier.width(640.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Text(exitError ?: if(!state.cameraRequested)"正在返回相册…" else if(state.savingPhoto)"正在保存照片…" else state.cameraMessage,
                        style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelPrimaryLight)
                    Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically){
                        Button(onClick={if(!state.cameraRequested)exitAttempt++ else vm.onEvent(CaptureEvent.CloseCamera)},enabled=!state.savingPhoto){Text(if(!state.cameraRequested)"返回相册" else "取消拍摄")}
                        Button(onClick={vm.onEvent(CaptureEvent.Shutter)},
                            enabled=state.cameraRequested && !state.savingPhoto && state.cameraFrame?.let{now-it.receivedAt<=1500L}==true,
                            modifier=Modifier.heightIn(min=64.dp).semantics{contentDescription="快门，拍摄当前取景照片"}){
                            Text("拍照")
                        }
                    }
                }
            }
        }
    )
}

/** Native image-backed framing marks stay visible in an AttachmentPanel. */
@Composable private fun ViewfinderCorners(modifier:Modifier=Modifier){
    Icon(painterResource(R.drawable.ic_viewfinder_frame),"有效拍摄范围，四角内的画面将保存",modifier,tint=PicoTheme.colorScheme.labelPrimaryLight)
}
