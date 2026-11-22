package com.example.spatialphoto

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spatialphoto.data.repository.FilePhotoLibraryRepository
import com.example.spatialphoto.data.repository.OnnxSharpRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Checks compressed APK assets on fresh private storage without clearing the user's app. */
@RunWith(AndroidJUnit4::class)
class FirstModelPreparationDeviceTest {
    @Test fun unpackAndPredictInIsolatedStorage() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = FilePhotoLibraryRepository(File(app.filesDir, "captures")).list().first().photo
        val isolated = File(app.filesDir, "first-model-preparation33")
        check(!isolated.exists()) { "Previous test directory exists; inspect before retrying" }
        isolated.mkdirs()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = isolated
        }
        val repository = OnnxSharpRepository(context)
        val stages = mutableListOf<String>()
        fun hash(input: InputStream): String = input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(1024 * 1024)
            while (true) {
                val count = it.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
            digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
        val originalHash = hash(File(photo.path).inputStream())
        try {
            // An interrupted older copy must not be mistaken for a prepared model.
            File(isolated, "models").mkdirs()
            val graph = if (BuildConfig.WEIGHT_ONLY_MODEL) "sharp-w8.onnx" else "sharp-hd.onnx"
            File(isolated, "models/$graph.partial").writeText("interrupted test copy")
            val output = repository.predict(photo) { stages += it }
            assertTrue(output.count > 0)
            assertTrue(stages.contains("首次准备端上模型…"))
            assertTrue(stages.contains("加载重建模型…"))
            val files = checkNotNull(File(isolated, "models").listFiles())
            assertFalse(files.any { it.name.endsWith(".partial") })
            val hashes = JSONObject()
            files.forEach { model ->
                val copied = hash(model.inputStream())
                assertEquals(model.name, hash(app.assets.open("models/${model.name}")), copied)
                hashes.put(model.name, copied)
            }
            assertEquals(originalHash, hash(File(photo.path).inputStream()))
            File(app.filesDir, "first-model-preparation33.json").writeText(JSONObject()
                .put("success", true).put("modelHashes", hashes)
                .put("stages", stages).put("pointCount", output.count)
                .put("timing", repository.lastTiming.toString())
                .put("originalUnchanged", true).toString(2))
        } finally {
            repository.release()
            isolated.deleteRecursively()
        }
    }
}
