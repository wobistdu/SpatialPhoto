package com.example.spatialphoto.data.repository

import java.io.File
import kotlin.math.ceil

/** Leave room for photos, intermediate PLYs and atomic writes. */
internal fun requireStorageSpace(directory:File,additionalBytes:Long=0) {
    val required=additionalBytes+256L*1024*1024
    val available=directory.usableSpace
    check(available>=required) {
        val missing=ceil((required-available).toDouble()/(1024*1024)).toLong()
        "存储空间不足，请至少释放 $missing MB 后重试。已保存的原图仍在相册中"
    }
}
