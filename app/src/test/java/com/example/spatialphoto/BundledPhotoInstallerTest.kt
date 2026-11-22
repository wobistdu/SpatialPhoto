package com.example.spatialphoto

import com.example.spatialphoto.data.repository.BundledPhotoInstaller
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BundledPhotoInstallerTest {
    private val payload = "sample-photo".toByteArray()
    private val asset = BundledPhotoInstaller.Asset("photo.png", payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
    private val sample = BundledPhotoInstaller.Sample("sample-test-01", listOf(asset))
    private fun runTest(block: (File, File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("builtin-photos").toFile()
        try { block(File(root,"captures"), File(root,"state")) } finally { root.deleteRecursively() }
    }
    @Test fun importsOnceAndRespectsDeletionAcrossRestart() = runTest { captures,state ->
        var reads=0
        fun installer()=BundledPhotoInstaller(captures,state){reads++;payload.inputStream()}
        installer().install(listOf(sample))
        assertArrayEquals(payload,File(captures,"${sample.id}/photo.png").readBytes())
        installer().install(listOf(sample));assertEquals(1,reads)
        File(captures,sample.id).deleteRecursively()
        installer().install(listOf(sample));assertEquals(1,reads)
        assertFalse(File(captures,sample.id).exists())
    }
    @Test fun corruptCopyNeverPublishesAndCanRetry() = runTest { captures,state ->
        try {
            BundledPhotoInstaller(captures,state){"wrong-data!".byteInputStream()}.install(listOf(sample))
            fail("corrupt copy accepted")
        } catch(expected:IllegalStateException) { }
        assertFalse(File(captures,sample.id).exists())
        assertFalse(File(state,"${sample.id}.installed").exists())
        BundledPhotoInstaller(captures,state){payload.inputStream()}.install(listOf(sample))
        assertArrayEquals(payload,File(captures,"${sample.id}/photo.png").readBytes())
    }
    @Test fun interruptedCopyDoesNotExposePartialPhoto() = runTest { captures,state ->
        val two=sample.copy(assets=listOf(asset,asset.copy(name="scene.ply")))
        try {
            BundledPhotoInstaller(captures,state){if(it.endsWith("scene.ply"))throw java.io.IOException("interrupted") else payload.inputStream()}.install(listOf(two))
            fail("interruption ignored")
        } catch(expected:java.io.IOException) { }
        assertFalse(File(captures,sample.id).exists())
        assertTrue(state.listFiles()!!.isEmpty())
        BundledPhotoInstaller(captures,state){payload.inputStream()}.install(listOf(two))
        assertTrue(File(captures,"${sample.id}/scene.ply").isFile)
    }
    @Test fun existingDirectoryIsNotOverwritten() = runTest { captures,state ->
        val target=File(captures,sample.id).apply{mkdirs()}
        File(target,"photo.png").writeText("existing")
        BundledPhotoInstaller(captures,state){error("must not read asset")}.install(listOf(sample))
        assertEquals("existing",File(target,"photo.png").readText())
    }
    @Test fun traversalIsRejected() = runTest { captures,state ->
        try {
            BundledPhotoInstaller(captures,state){payload.inputStream()}.install(listOf(sample.copy(assets=listOf(asset.copy(name="../photo.png")))))
            fail("traversal accepted")
        } catch(expected:IllegalArgumentException) { }
        assertTrue(captures.listFiles()!!.isEmpty())
    }
}
