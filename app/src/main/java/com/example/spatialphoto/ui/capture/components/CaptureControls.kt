package com.example.spatialphoto.ui.capture.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.spatialphoto.R
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.design.icons.PicoIcons
import com.pico.spatial.ui.design.icons.outlined.Camera3d
import com.pico.spatial.ui.design.icons.outlined.Delete
import com.pico.spatial.ui.design.icons.outlined.Ellipsis
import com.pico.spatial.ui.design.icons.outlined.Download
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.windows.AlertDialog
import com.example.spatialphoto.domain.model.PhotoItem
import com.example.spatialphoto.ui.capture.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun photoDate(item:PhotoItem):String {
    item.displayTitle?.let{return it}
    val stamp=item.id.substringBeforeLast('/').substringAfterLast('-').toLongOrNull() ?: return "空间照片"
    return SimpleDateFormat("M月d日 · HH:mm",Locale.CHINA).format(Date(stamp))
}
@Composable fun CaptureControls(state:CaptureUiState,onEvent:(CaptureEvent)->Unit,onHelp:()->Unit={}) {
    val exportEnabled=!state.exporting && !state.exportNeedsPermission && !state.loading && state.photos.isNotEmpty() && state.deletingIds.isEmpty()
    val captureEnabled=!state.cameraRequested && !state.cameraClosing && !state.cameraNeedsPermission && !state.savingPhoto && !state.loading && state.deletingIds.isEmpty()
    TitleBar(modifier=Modifier.fillMaxWidth(),titleAlignment=TitleAlignment.Center,
        title={Text(if(com.example.spatialphoto.BuildConfig.DEPTH_SMALL_MODEL)"浮光 · 轻量实验" else stringResource(R.string.app_name))},
        leadingActions={Button(onClick=onHelp){Text("帮助")}},
        trailingActions={Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ContrastHover(enabled=exportEnabled) { hoverTarget ->
            Button(onClick={onEvent(CaptureEvent.ExportAll)},modifier=hoverTarget,enabled=exportEnabled) {
                Icon(PicoIcons.Outlined.Download,null,Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp));Text(if(state.exporting)"导出中…" else "全部导出")
            }
            }
            ContrastHover(enabled=captureEnabled) { hoverTarget ->
            Button(onClick={onEvent(CaptureEvent.OpenCamera)},modifier=hoverTarget,enabled=captureEnabled){
            Icon(PicoIcons.Outlined.Camera3d,contentDescription=null,modifier=Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp));Text(if(state.cameraRequested)"准备取景…" else "拍照")
        }}}})
    if(state.exportMessage.isNotBlank())Text(state.exportMessage,modifier=Modifier.padding(horizontal=32.dp,vertical=8.dp),style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
    Row(Modifier.fillMaxWidth().padding(start=32.dp,end=32.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(if(state.photos.isEmpty())"把眼前，留在身边" else if(state.photos.any{it.builtInSample})"照片与内置示例" else "最近拍摄",style=PicoTheme.typography.titleMedium,color=PicoTheme.colorScheme.labelPrimary,modifier=Modifier.weight(1f))
        val tasks=listOfNotNull(
            state.processingCount.takeIf{it>0}?.let{"$it 张处理中"},
            state.waitingCount.takeIf{it>0}?.let{"$it 张排队"},
        )
        Text(if(tasks.isNotEmpty())tasks.joinToString(" · ")+" · 可继续拍照" else "${state.photos.size} 张照片",style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
    }
}
@Composable internal fun SavedPhoto(path:String,description:String,modifier:Modifier=Modifier,thumbnail:Boolean=false) {
    var retry by remember(path){mutableIntStateOf(0)}
    val result by produceState<Pair<Boolean,ImageBitmap?>>(false to null,path,thumbnail,retry) {
        value=false to null
        val decoded=withContext(Dispatchers.IO) {
            runCatching{BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=if(thumbnail)2 else 1})?.asImageBitmap()}.getOrNull()
        }
        value=true to decoded
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        val bitmap=result.second
        if(bitmap!=null)Image(bitmap,description,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else if(!result.first)CircularProgressIndicator()
        else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("原图无法读取",style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
            if(!thumbnail)Button(onClick={retry++}){Text("重新读取")}
        }
    }
}
@Composable internal fun PhotoStatus(item:PhotoItem,queuePosition:Int=0) {
    val status=when {
        item.queued->"等待重建 · 第 ${queuePosition.coerceAtLeast(1)} 张"
        item.previewQueued->"等待准备 3D 浏览"
        item.progress?.contains("首次准备")==true->"首次准备本机模型 · 请稍候"
        item.progress?.contains("加载重建模型")==true->"正在加载模型 · 请稍候"
        item.progress?.contains("准备 3D")==true->"正在准备 3D 浏览 · 预计 ${item.percent ?: 98}%"
        item.progress?.contains("Gaussian")==true->"正在整理空间细节 · 预计 ${item.percent ?: 97}%"
        item.progress?.contains("保存空间")==true->"正在保存空间照片 · 预计 99%"
        item.progress!=null->"正在重建 · 预计 ${item.percent ?: 1}%"
        item.error!=null->"重建未完成 · 可重试"
        item.ready && item.builtInSample->"AI 生成 · 内置空间照片"
        item.ready->if(item.scene!!.resolution>=768)"高清空间照片" else "空间照片"
        else->"原始照片"
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(item.processing)CircularProgressIndicator(modifier=Modifier.size(16.dp))
        Text(status,style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
    }
    if(item.processing && item.progress?.contains("模型")!=true)LinearProgressIndicator(progress={ (item.percent ?: 1)/100f },modifier=Modifier.fillMaxWidth())
    }
}
@Composable internal fun PhotoTile(item:PhotoItem,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,queuePosition:Int=0,onDelete:()->Unit={},deleteEnabled:Boolean=true) {
    var menuOpen by remember(item.id){mutableStateOf(false)}
    var confirmDelete by remember(item.id){mutableStateOf(false)}
    if(confirmDelete)AlertDialog(
        onDismissRequest={confirmDelete=false},
        title={Text("删除这张照片？")},
        content={Text("${photoDate(item)} 的原图和应用内 3D 照片会一并删除，无法恢复。已导出的文件会保留。")},
        buttons={
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Button(onClick={confirmDelete=false}){Text("保留照片")}
                Button(onClick={confirmDelete=false;onDelete()},enabled=enabled && deleteEnabled,
                    colors=ButtonDefaults.buttonColors(containerColor=PicoTheme.colorScheme.error,contentColor=PicoTheme.colorScheme.labelPrimaryLight)){Text("确认删除")}
            }
        })
    // Thumbnail and menu are siblings, never nested click targets. In particular,
    // hovering the photograph must not scale/move the ellipsis under the pointer.
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(item.photo.width.toFloat()/item.photo.height)) {
            SavedPhoto(item.photo.path,if(item.builtInSample)"AI 生成的国风人物图" else "拍摄的照片",
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .clickable(enabled=enabled && !menuOpen,onClick=onClick)
                    .semantics{contentDescription="查看照片 ${photoDate(item)}"},thumbnail=true)
            // Keep the accepted size/layout. ContrastHover strengthens the faint
            // default feedback; the native button keeps click, focus and semantics.
            Box(Modifier.align(Alignment.TopEnd).zIndex(1f).padding(8.dp)) {
                ContrastHover(enabled=enabled) { hoverTarget ->
                IconButton(onClick={menuOpen=true},enabled=enabled,modifier=Modifier.size(48.dp).then(hoverTarget),size=IconButtonDefaults.iconButtonSize(48.dp)) {
                    if(enabled)Icon(PicoIcons.Outlined.Ellipsis,"照片选项 ${photoDate(item)}",Modifier.size(20.dp))
                    else CircularProgressIndicator(Modifier.size(24.dp))
                }
                }
                if(menuOpen)Menu(onDismissRequest={menuOpen=false}) {
                    MenuItem(title={Text(if(deleteEnabled)"删除照片" else "导出中，暂不可删除")},
                        leadingIcon={Icon(PicoIcons.Outlined.Delete,null,Modifier.size(24.dp))},
                        onClick={menuOpen=false;if(deleteEnabled)confirmDelete=true})
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(photoDate(item),style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(4.dp))
        PhotoStatus(item,queuePosition)
        Spacer(Modifier.height(8.dp))
    }
}
