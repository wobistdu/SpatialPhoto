package com.example.spatialphoto.data.repository

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** Bundled photos use the same gallery, export and renderer paths as captured photos. */
class BuiltinPhotoLibraryRepository(context: Context, private val delegate: PhotoLibraryRepository) : PhotoLibraryRepository by delegate {
    private val assets = context.applicationContext.assets
    private val installer = BundledPhotoInstaller(File(context.filesDir, "captures"),
        File(context.filesDir, "builtin-photo-installation")) { assets.open(it) }
    private val mutex = Mutex()
    private var initialized = false

    override suspend fun list() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!initialized) {
                val catalog = assets.open("builtin_photos/index.json").bufferedReader().use { JSONArray(it.readText()) }
                val samples = (0 until catalog.length()).map { index ->
                    val item = catalog.getJSONObject(index)
                    val files = item.getJSONArray("files")
                    val names = (0 until files.length()).map { files.getJSONObject(it).getString("name") }
                    require(names.containsAll(listOf("photo.png", "camera.json", "scene.json", "scene.ply")))
                    require(names.count { it.startsWith("color-v1-") && it.endsWith(".ply") } == 1)
                    require(names.count { it.startsWith("window-photo-v8-") && it.endsWith(".ply") } == 1)
                    BundledPhotoInstaller.Sample(item.getString("id"), (0 until files.length()).map { i ->
                        val file = files.getJSONObject(i)
                        BundledPhotoInstaller.Asset(file.getString("name"), file.getLong("bytes"), file.getString("sha256"))
                    })
                }
                installer.install(samples)
                initialized = true
            }
            delegate.list()
        }
    }
}
