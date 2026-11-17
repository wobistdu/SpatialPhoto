package com.example.spatialphoto.platform

import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

import android.os.Bundle
import android.content.pm.PackageManager

class LaunchActivity : SpatialLaunchActivity() {
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val missing=arrayOf("android.permission.CAMERA","com.picovr.permission.SPATIAL_DATA").filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
  if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),71)
 }
}
