package com.example.spatialphoto.ui.capture.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.design.icons.PicoIcons
import com.pico.spatial.ui.design.icons.outlined.ArrowLeft
import com.example.spatialphoto.R
import com.example.spatialphoto.BuildConfig

@Composable internal fun PhotoHelp(firstUse:Boolean,onClose:()->Unit,onCapture:()->Unit) {
    val lightweight=BuildConfig.DEPTH_SMALL_MODEL
    Column(Modifier.fillMaxSize()) {
        TitleBar(titleAlignment=TitleAlignment.Center,title={Text(if(firstUse)"遇见浮光" else "使用帮助")},
            leadingActions={
                IconButton(onClick=onClose,modifier=Modifier.size(48.dp),size=IconButtonDefaults.iconButtonSize(48.dp)) {
                    Icon(PicoIcons.Outlined.ArrowLeft,"返回相册",Modifier.size(24.dp))
                }
            })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=40.dp),
            verticalArrangement=Arrangement.spacedBy(22.dp)) {
            if(firstUse) {
                Column(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.mipmap.ic_spatial_launcher),null,Modifier.size(80.dp))
                    Text("拍下一张照片，换个角度重温",style=PicoTheme.typography.titleLarge,color=PicoTheme.colorScheme.labelPrimary)
                }
                HelpItem("拍一个有前后层次的场景","光线充足的静物最适合。对准场景，保持头部稳定，再按快门。")
                HelpItem(if(lightweight)"通常十几秒，照片拥有浅景深" else "约一分钟，照片拥有深度",
                    "等待时可以继续拍照或浏览原图。第一次使用需要额外准备本机模型。")
                HelpItem("轻轻侧头，重新看见",if(lightweight)"点击完成的照片进入 3D，小幅左右移动头部。深度为 AI 估计，边缘背景会拉伸，不代表真实尺度。"
                    else "点击完成的照片进入 3D，小幅左右移动头部。AI 推测的遮挡区域可能变形。")
                Text("照片只在本机处理。使用帮助里可以查看导出和保管方法。",style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
            } else {
            Text("拍下一张照片，换个角度重温",style=PicoTheme.typography.titleLarge,color=PicoTheme.colorScheme.labelPrimary)
            HelpItem("01  选一个有层次的场景","对准近处的静物和远处的背景，保持光线充足、头部稳定。玻璃、镜面、快速移动的人物可能出现破洞或变形。")
            HelpItem("02  拍完就可以继续",(if(lightweight)"轻量实验版通常等待十几秒，首次准备模型会更久。" else "重建通常约一分钟，首次准备模型会更久。")+
                "可以继续拍照或浏览原图；照片按顺序处理，重启后会继续待处理队列。")
            HelpItem("03  小幅侧头，感受深度",if(lightweight)
                "这是根据单张照片估计的浅景深效果，没有真实尺寸。小幅侧头观看；边缘背景通过附近颜色延展，可能拉伸或出现空隙，不适合绕到背面观看。"
                else "点击完成的照片即可进入 3D。轻轻左右移动头部，效果最自然；遮挡区域由 AI 推测，照片不适合绕到背面观看。")
            HelpItem("照片由你保管","照片和重建都在头显本机处理，不上传云端。卸载会移除应用内照片，建议先「全部导出」。导出包含 PNG 原图和已完成的 PLY 空间照片。")
            HelpItem("导出后在哪里找","首次导出时选择 DCIM 或 Pictures。之后可在该目录的 SpatialPhoto 文件夹找到照片；重复导出会跳过相同文件。PLY 需要支持高斯泼溅的查看器。"+
                if(lightweight)"轻量版 PLY 的尺寸是相对尺度，不能用于测量。" else "")
            HelpItem("遇到问题","点照片可查看失败原因并重试；3D 加载失败时可以重试加载或返回原图。反馈时请附版本 ${BuildConfig.VERSION_NAME}、失败提示和发生的步骤。")
            if(lightweight)HelpItem("轻量模型","Depth Anything V2 Small · Apache-2.0。许可证及转换说明随应用提供，照片在本机处理。")
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(32.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Button(onClick=onClose,modifier=Modifier.weight(1f)){Text("进入相册")}
            Button(onClick=onCapture,modifier=Modifier.weight(1f)){Text("去拍一张")}
        }
    }
}

@Composable private fun HelpItem(title:String,body:String) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=PicoTheme.typography.titleSmall,color=PicoTheme.colorScheme.labelPrimary)
        Text(body,style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
    }
}
