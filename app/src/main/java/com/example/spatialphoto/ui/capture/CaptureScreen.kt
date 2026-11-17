package com.example.spatialphoto.ui.capture

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import com.pico.spatial.ui.design.icons.outlined.ArrowRight
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.spatialphoto.R
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.design.icons.PicoIcons
import com.pico.spatial.ui.design.icons.outlined.ArrowLeft
import com.pico.spatial.ui.design.icons.outlined.Cube
import com.pico.spatial.ui.design.icons.outlined.Camera3d
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.design.*
import com.example.spatialphoto.ui.capture.components.*

/** Read-only diagnostics observe actual product state, not replacement data. */
object CaptureDiagnostics {
    @Volatile var materialVisible:Boolean=true
    @Volatile var windowSize:IntSize=IntSize.Zero
    @Volatile var captureStageActive:Boolean=false
    @Volatile var viewfinderSize:IntSize=IntSize.Zero
    @Volatile var viewModel:CaptureViewModel?=null
    @Volatile var attachedPath:String?=null
    @Volatile var fitVerified:Boolean=false
    @Volatile var previewLiveCount:Int=0
}
@Composable fun CaptureScreen() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val app=context.applicationContext as com.example.spatialphoto.platform.SpatialApplication
    val vm:CaptureViewModel=viewModel(viewModelStoreOwner=app,factory=app.captureViewModelFactory)
    val state by vm.state.collectAsState()
    LaunchedEffect(state.exportNeedsPermission) {
        if(state.exportNeedsPermission)try {
            context.startActivity(android.content.Intent(context,com.example.spatialphoto.platform.ExportPermissionActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch(f:Exception){vm.onEvent(CaptureEvent.ExportPermissionResult(false,"无法打开目录授权：${f.message}"))}
    }
    val navigator=LocalSpatialNavigator.current
    LaunchedEffect(state.cameraRequested,state.cameraSession) {
        if(state.cameraRequested) {
            try {
                // Keep the gallery usable until a calibrated live image is ready.
                kotlinx.coroutines.withTimeout(9000) {
                    while(vm.state.value.cameraFrame==null && vm.state.value.cameraRequested)kotlinx.coroutines.delay(25)
                }
                if(!vm.state.value.cameraRequested)return@LaunchedEffect
                val result=kotlinx.coroutines.withTimeout(8000){navigator.openStage("PhotoCapture",style=StageStyle.Mixed)}
                if(result!=OpenStageResult.Allowed)vm.onEvent(CaptureEvent.CameraFailed("无法进入拍摄空间：$result"))
            } catch(timeout:kotlinx.coroutines.TimeoutCancellationException){vm.onEvent(CaptureEvent.CameraFailed("打开相机超时，请重试"))}
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(failure:Exception){vm.onEvent(CaptureEvent.CameraFailed("无法打开相机：${failure.message}"))}
        }
    }
    val density=androidx.compose.ui.platform.LocalDensity.current
    val physicalLength=LocalPhysicalLengthConverter.current
    var windowReady by remember{mutableStateOf(false)}
    LaunchedEffect(state.viewing3D) {
        windowReady=false
        try {
            // Larger ordinary window approximates the capture's angular extent at normal seating distance.
            val width=if(state.viewing3D)900 else 840
            val height=if(state.viewing3D)800 else 720
            check(com.pico.spatial.ui.platform.resize.SpatialContainerUtils.resizeCurrentWindowContainer(context,
                with(density){width.dp.roundToPx()},with(density){height.dp.roundToPx()})){"窗口大小调整失败"}
            windowReady=true
        } catch(c:kotlinx.coroutines.CancellationException){throw c}
        catch(f:Exception){
            vm.onEvent(CaptureEvent.RenderFailed(f.message ?: "窗口切换失败"))
            if(state.viewing3D)vm.onEvent(CaptureEvent.Exit3D)
        }
    }
    var windowSize by remember{mutableStateOf(IntSize.Zero)}
    DisposableEffect(vm){CaptureDiagnostics.viewModel=vm;onDispose{CaptureDiagnostics.viewModel=null}}
    // The container itself stays transparent (manifest materialbackground=0).
    // The 2D page owns its glass node, so leaving 2D removes it with the content;
    // no asynchronous container-background request can outlive the page.
    SideEffect { CaptureDiagnostics.materialVisible=!state.viewing3D }
    val pageMaterial=if(state.viewing3D)Modifier else Modifier.clip(RoundedCornerShape(24.dp)).backgroundMaterial()
    Column(Modifier.fillMaxSize().then(pageMaterial).onSizeChanged{windowSize=it;CaptureDiagnostics.windowSize=it}) {
        val selected=state.selected
        if(selected==null) {
            CaptureControls(state,vm::onEvent)
            if(state.message.isNotBlank())Text(state.message,modifier=Modifier.padding(horizontal=32.dp),style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelPrimary)
            if(!state.loading && state.photos.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=androidx.compose.ui.Alignment.Center){
                Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Icon(PicoIcons.Outlined.Camera3d,null,Modifier.size(48.dp),tint=PicoTheme.colorScheme.labelSecondary)
                    Text("拍下第一张空间照片",style=PicoTheme.typography.titleMedium)
                    Text("记录眼前的场景，稍后换个角度重温",style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
                    Button(onClick={vm.onEvent(CaptureEvent.OpenCamera)},enabled=!state.savingPhoto){Text("开始拍摄")}
                }
            } else {
                val queued=state.photos.filter{it.queued}.sortedBy{it.queuedAt}.map{it.id}
                LazyVerticalGrid(columns=GridCells.Adaptive(240.dp),modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(start=32.dp,end=32.dp,bottom=32.dp),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    items(state.photos,key={it.id}){item->PhotoTile(item,{vm.onEvent(CaptureEvent.Open(item.id))},enabled=item.id !in state.deletingIds,queuePosition=queued.indexOf(item.id)+1,onDelete={vm.onEvent(CaptureEvent.Delete(item.id))},deleteEnabled=!state.exporting)}
                }
            }
        } else {
            val index=state.photos.indexOfFirst{it.id==selected.id}
            val swipe=Modifier.pointerInput(selected.id) {
                var horizontal=0f
                detectHorizontalDragGestures(onDragStart={horizontal=0f},onDragCancel={horizontal=0f},
                    onDragEnd={if(kotlin.math.abs(horizontal)>72.dp.toPx())vm.onEvent(CaptureEvent.Browse(if(horizontal<0)1 else -1))}) { change,amount->
                    horizontal+=amount;change.consume()
                }
            }.semantics {
                contentDescription="照片浏览，左右滑动切换"
                customActions=listOf(CustomAccessibilityAction("上一张"){vm.onEvent(CaptureEvent.Browse(-1));true},CustomAccessibilityAction("下一张"){vm.onEvent(CaptureEvent.Browse(1));true})
            }
            if(!state.viewing3D)TitleBar(modifier=Modifier.fillMaxWidth(),titleAlignment=TitleAlignment.Center,
                title={if(!state.viewing3D)Text(photoDate(selected))},
                leadingActions={IconButton(onClick={vm.onEvent(CaptureEvent.Back)},modifier=Modifier.size(48.dp),size=IconButtonDefaults.iconButtonSize(48.dp)){
                    Icon(PicoIcons.Outlined.ArrowLeft,if(state.viewing3D)"返回 2D 照片" else "返回相册",Modifier.size(24.dp))
                }},
                trailingActions={
                    if(!state.viewing3D) {
                        IconButton(onClick={vm.onEvent(CaptureEvent.Enter3D)},enabled=selected.ready,modifier=Modifier.size(48.dp),size=IconButtonDefaults.iconButtonSize(48.dp)){
                            Icon(PicoIcons.Outlined.Cube,"进入 3D 模式",Modifier.size(24.dp))
                        }
                    }
                })
            if(state.viewing3D) {
                // A → B → A can be coalesced before Compose draws B. Each request must
                // still complete its own loading state, even when the final path is unchanged.
                Spacer(Modifier.weight(1f).fillMaxWidth())
                if(windowReady && windowSize!=IntSize.Zero) {
                    val baseWidth=with(density){windowSize.width.toDp()}
                    val baseHeight=(with(density){windowSize.height.toDp()}-140.dp).coerceAtLeast(1.dp)
                    // Wearer-observed native Gaussian occlusion persists at peripheral angles
                    // across companion windows. Keep the accepted size and add physical clearance.
                    val photoRecessMeters=.71f
                    // A separate transparent companion provides its own clipping volume.
                    // Both companions use the SDK's horizontal Center alignment directly.
                    // Extra half-width offsets double-compensate the actual compositor placement.
                    Augment(anchor=NormalizedPoint3D.Center.copy(y=baseHeight.value/(2*(baseHeight.value+140f))),
                        alignment=AugmentContentAlignment.Center,
                        offset=DpOffset3D(0.dp,0.dp,-physicalLength.lengthToDp(photoRecessMeters,LengthUnit.Meters)),
                        enableMaterialBackground=false) {
                        key(state.previewRevision) {
                            WindowGaussianPreview(selected.scene!!.path,selected.photo,windowSize,
                                {if(vm.state.value.previewRevision==state.previewRevision)vm.onEvent(CaptureEvent.Rendered)},{if(vm.state.value.previewRevision==state.previewRevision)vm.onEvent(CaptureEvent.RenderFailed(it))},
                                Modifier.size(baseWidth*1.75f,baseHeight*1.75f),onSwipe={vm.onEvent(CaptureEvent.Browse(it))},
                                presentationScale=1.75f)
                        }
                    }
                }
                // Keep controls in their own foreground companion. Peripheral native
                // Gaussian ordering still needs physical separation, validated by the wearer.
                Spacer(Modifier.height(140.dp))
                // A real companion window in front of the bounded Gaussian volume.
                // Front + 16dp keeps hit testing ahead of the main window plane,
                // including after restore/resize. Center anchors may put controls behind it.
                Augment(anchor=NormalizedPoint3D.Front.copy(y=.90f),alignment=AugmentContentAlignment.BottomCenter,
                    offset=DpOffset3D(0.dp,0.dp,16.dp),enableMaterialBackground=true,cornerRadius=24.dp) {
                    Row(Modifier.padding(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Button(onClick={
                            android.util.Log.i("SpatialPhoto","RETURN_2D_CLICK viewing3D=${vm.state.value.viewing3D}")
                            vm.onEvent(CaptureEvent.Exit3D)
                        }) {
                            Icon(PicoIcons.Outlined.ArrowLeft,null,Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp));Text("返回 2D 照片")
                        }
                        PhotoNavigation(index,state.photos.size,vm::onEvent)
                        if(state.previewLoading || !windowReady) {
                            Icon(painterResource(R.drawable.ic_hourglass),"加载中",Modifier.size(24.dp),tint=PicoTheme.colorScheme.labelPrimary)
                            Text("正在加载 3D 照片…",style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelPrimary)
                            CircularProgressIndicator(modifier=Modifier.size(24.dp))
                        } else Text(state.previewMessage,modifier=Modifier.widthIn(max=210.dp),style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
                    }
                }
            } else {
                SavedPhoto(selected.photo.path,"原始照片",Modifier.weight(1f).fillMaxWidth().padding(horizontal=24.dp).then(swipe))
                // Anchor navigation to the page centre, independent of status text width.
                Box(Modifier.fillMaxWidth().padding(24.dp)) {
                    Column(Modifier.align(androidx.compose.ui.Alignment.CenterStart).widthIn(max=240.dp)) {
                        PhotoStatus(selected,state.photos.filter{it.queued}.sortedBy{it.queuedAt}.indexOfFirst{it.id==selected.id}+1)
                        if(selected.ready)Text("点右上角立方体，进入 3D 模式",style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
                        if(selected.progress!=null)Text("可以返回相册继续拍照",style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
                    }
                    Box(Modifier.align(androidx.compose.ui.Alignment.Center)) { PhotoNavigation(index,state.photos.size,vm::onEvent) }
                    if(selected.error!=null)Button(onClick={vm.onEvent(CaptureEvent.Retry(selected.id))},modifier=Modifier.align(androidx.compose.ui.Alignment.CenterEnd)){Text("重新重建")}
                }
            }
        }
    }
}

@Composable private fun PhotoNavigation(index:Int,total:Int,onEvent:(CaptureEvent)->Unit) {
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        IconButton(onClick={onEvent(CaptureEvent.Browse(-1))},enabled=index>0,modifier=Modifier.size(48.dp),size=IconButtonDefaults.iconButtonSize(48.dp)) {Icon(PicoIcons.Outlined.ArrowLeft,"上一张",Modifier.size(24.dp))}
        Text("${index+1} / $total",style=PicoTheme.typography.bodySmall)
        IconButton(onClick={onEvent(CaptureEvent.Browse(1))},enabled=index>=0 && index<total-1,modifier=Modifier.size(48.dp),size=IconButtonDefaults.iconButtonSize(48.dp)) {Icon(PicoIcons.Outlined.ArrowRight,"下一张",Modifier.size(24.dp))}
    }
}
