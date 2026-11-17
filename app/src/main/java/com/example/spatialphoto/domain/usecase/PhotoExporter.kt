package com.example.spatialphoto.domain.usecase
import com.example.spatialphoto.domain.model.PhotoItem
interface PhotoExporter {
    fun hasDestination():Boolean
    suspend fun export(photos:List<PhotoItem>,progress:(Int,Int)->Unit):String
}
