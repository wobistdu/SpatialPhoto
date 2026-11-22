package com.example.spatialphoto.platform

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.example.spatialphoto.ui.capture.CaptureEvent
import com.example.spatialphoto.ui.capture.CaptureViewModel

/** A short-lived bridge to system permission UI; gallery and queue keep their owner. */
class CameraPermissionActivity:Activity() {
    companion object {
        private val permissions=arrayOf("android.permission.CAMERA","com.picovr.permission.SPATIAL_DATA")
        fun isGranted(context:Context)=permissions.all{context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(isGranted(this))complete()
        else if(savedInstanceState==null)try {
            requestPermissions(permissions.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}.toTypedArray(),71)
        } catch(failure:Exception){complete()}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==71)complete()
    }
    private fun complete() {
        val app=application as SpatialApplication
        ViewModelProvider(app,app.captureViewModelFactory)[CaptureViewModel::class.java]
            .onEvent(CaptureEvent.CameraPermissionResult(isGranted(this)))
        finish()
    }
}
