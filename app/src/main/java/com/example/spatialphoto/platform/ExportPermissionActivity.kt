package com.example.spatialphoto.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import com.example.spatialphoto.data.repository.GalleryPhotoExporter
import com.example.spatialphoto.ui.capture.*

/** System directory grant only. The app's gallery remains in its Spatial window. */
class ExportPermissionActivity:Activity() {
    private val vm get()=(application as SpatialApplication).let{ViewModelProvider(it,it.captureViewModelFactory)[CaptureViewModel::class.java]}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null)try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM"))
            },13)
        } catch(f:Exception){complete(false,"系统无法打开目录授权：${f.message}")}
    }
    @Deprecated("Platform activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=13)return
        val tree=data?.data
        if(resultCode!=RESULT_OK || tree==null){complete(false,"已取消导出，照片仍保留在应用中");return}
        try {GalleryPhotoExporter(this).remember(tree,data.flags);complete(true,"")}
        catch(f:Exception){complete(false,f.message ?: "目录授权失败")}
    }
    private fun complete(granted:Boolean,message:String) {vm.onEvent(CaptureEvent.ExportPermissionResult(granted,message));finish()}
}
