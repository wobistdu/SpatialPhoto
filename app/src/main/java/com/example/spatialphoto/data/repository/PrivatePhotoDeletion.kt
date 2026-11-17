package com.example.spatialphoto.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Deletes exactly one private photo directory; never follows symbolic links. */
internal object PrivatePhotoDeletion {
    fun delete(captures:File,photo:File) {
        val root=captures.canonicalFile
        val dir=photo.absoluteFile.parentFile ?: error("照片路径无效")
        require(photo.name=="photo.png" && dir.name.matches(Regex("(?:photo-[0-9]+|sample-[a-z0-9-]+)"))){"照片路径无效"}
        require(dir.absoluteFile.parentFile?.canonicalFile==root && !Files.isSymbolicLink(dir.toPath())){"禁止删除相册外文件"}
        if(!dir.exists())return
        require(Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS)){"照片目录无效"}
        val tombstone=File(root,".deleted-${dir.name}-${UUID.randomUUID()}")
        check(dir.renameTo(tombstone)){"删除失败，照片仍保留"}
        // Rename atomically removes the record from the library before file cleanup.
        try {
            Files.walk(tombstone.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        } catch(failure:Exception) {
            // Never resurrect a partially deleted photo. Surface cleanup failure to caller.
            throw PhotoCleanupPendingException("照片已移除，文件清理未完成：${failure.message}")
        }
    }
}
internal class PhotoCleanupPendingException(message:String):Exception(message)
