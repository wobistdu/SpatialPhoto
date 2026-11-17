package com.example.spatialphoto.data.repository

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Publish a complete sample atomically, once. A user's deletion is never undone on restart. */
class BundledPhotoInstaller(
    private val captures: File,
    private val stateDirectory: File,
    private val openAsset: (String) -> InputStream,
) {
    data class Asset(val name: String, val bytes: Long, val sha256: String)
    data class Sample(val id: String, val assets: List<Asset>)

    @Synchronized fun install(samples: List<Sample>) {
        check(captures.mkdirs() || captures.isDirectory)
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory)
        for (sample in samples) {
            require(sample.id.matches(Regex("sample-[a-z0-9-]+")))
            require(sample.assets.isNotEmpty())
            require(sample.assets.map { it.name }.distinct().size == sample.assets.size)
            sample.assets.forEach {
                require(it.name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")) && it.name != "." && it.name != "..")
                require(it.bytes > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")))
            }
            val marker = File(stateDirectory, "${sample.id}.installed")
            if (marker.isFile) continue
            val target = File(captures, sample.id)
            if (!target.exists()) {
                val staged = File(stateDirectory, "${sample.id}.partial")
                if (staged.exists()) check(staged.deleteRecursively())
                check(staged.mkdir())
                try {
                    for (asset in sample.assets) {
                        val output = File(staged, asset.name)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        openAsset("builtin_photos/${sample.id}/${asset.name}").use { input ->
                            output.outputStream().buffered().use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    count += size
                                    check(count <= asset.bytes) { "Bundled photo exceeds manifest size" }
                                    digest.update(buffer, 0, size)
                                    sink.write(buffer, 0, size)
                                }
                            }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(count == asset.bytes && hash == asset.sha256) { "Bundled photo integrity check failed: ${sample.id}/${asset.name}" }
                    }
                    check(staged.renameTo(target)) { "Unable to publish bundled photo" }
                } finally { staged.deleteRecursively() }
            }
            // Also handles a process interruption between publishing the directory and marking it.
            // Never overwrite a directory already present at this reserved identifier.
            val pending = File(stateDirectory, "${sample.id}.marker-partial")
            pending.writeText("1\n")
            check(pending.renameTo(marker))
        }
    }
}
