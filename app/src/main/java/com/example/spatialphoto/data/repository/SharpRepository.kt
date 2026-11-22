package com.example.spatialphoto.data.repository

import android.content.Context
import com.example.spatialphoto.BuildConfig
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.*
import com.example.spatialphoto.domain.model.*
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.*
import android.os.SystemClock
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

interface SharpRepository {
    suspend fun predict(photo: CapturePhoto, progress: (String) -> Unit): GaussianOutput
    suspend fun release() {}
    fun requestRelease() {}
}

/** One CPU session per repository; all use/release operations share the same mutex. */
class OnnxSharpRepository(private val context: Context, private val idleTimeoutMillis: Long = 120_000, private val resolution:Int = ReconstructionQuality.INPUT_RESOLUTION, private val intraOpThreads:Int = 4) : SharpRepository {
    init {
        require(resolution in listOf(512,768));require(intraOpThreads in 1..8)
        require(!BuildConfig.WEIGHT_ONLY_MODEL || resolution==768) { "W8 candidate supports 768 only" }
    }
    private val modelName=if(BuildConfig.WEIGHT_ONLY_MODEL)"sharp-w8.onnx" else if(resolution==768)"sharp-hd.onnx" else "sharp-fp16.onnx"
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleRelease: Job? = null
    private var loaded: LoadedSession? = null
    @Volatile var lastTiming: PredictionTiming? = null
        private set

    data class PredictionTiming(val reused: Boolean, val prepareMs: Long, val loadMs: Long,
        val preprocessMs: Long, val inferenceMs: Long, val readbackMs: Long, val totalMs: Long)

    private class LoadedSession(val options: OrtSession.SessionOptions, val session: OrtSession) : Closeable {
        override fun close() { try { session.close() } finally { options.close() } }
    }

    /** Safe during inference: release waits for its native results and tensors to close. */
    override suspend fun release() = mutex.withLock {
        idleRelease?.cancel(); idleRelease = null
        val old = loaded; loaded = null
        old?.close()
        Unit
    }
    override fun requestRelease() { cleanupScope.launch { release() } }

    override suspend fun predict(photo: CapturePhoto, progress: (String) -> Unit): GaussianOutput = mutex.withLock {
        idleRelease?.cancel(); idleRelease = null
        lastTiming = null
        try { withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val env = OrtEnvironment.getEnvironment()
            val reused = loaded != null
            val root = File(context.filesDir, "models").apply { mkdirs() }
            val assets=if(BuildConfig.WEIGHT_ONLY_MODEL)listOf(modelName,"$modelName.data") else if(resolution==512)listOf(modelName,"sharp-fp16.onnx.data") else listOf(modelName,"$modelName.data","sharp-fp16.onnx.data")
            val expectedSizes=assets.associateWith { name ->
                when(name) {
                    "sharp-w8.onnx" -> BuildConfig.WEIGHT_ONLY_GRAPH_BYTES
                    "sharp-w8.onnx.data" -> BuildConfig.WEIGHT_ONLY_DATA_BYTES
                    "sharp-fp16.onnx" -> 7377195L
                    "sharp-hd.onnx.data" -> 1183744L
                    "sharp-hd.onnx" -> 7380493L
                    "sharp-fp16.onnx.data" -> 1306323328L
                    else -> error("Unknown model asset")
                }
            }
            val missing=if(reused)emptyMap() else expectedSizes.filter{(name,size)->File(root,name).length()!=size}
            // Discard only unfinished copies, never the current model or photos.
            missing.keys.forEach{File(root,"$it.partial").delete()}
            requireStorageSpace(root,missing.values.sum())
            for ((name,expected) in missing) {
                val target=File(root,name)
                    progress("首次准备端上模型…")
                    val partial = File(root, "$name.partial")
                    try {
                        context.assets.open("models/$name").use { input ->
                            partial.outputStream().use { output ->
                                val buffer=ByteArray(1024*1024)
                                while(true) {
                                    coroutineContext.ensureActive()
                                    val size=input.read(buffer)
                                    if(size<0)break
                                    output.write(buffer,0,size)
                                }
                            }
                        }
                        check(partial.length() == expected) { "模型大小校验失败，请重试" }
                        coroutineContext.ensureActive()
                        check(partial.renameTo(target)) { "模型保存失败，请检查存储空间" }
                    } finally {
                        partial.delete()
                    }
            }
            coroutineContext.ensureActive()
            val prepared = SystemClock.elapsedRealtime()
            if (loaded == null) {
                progress("加载重建模型…")
                val options = OrtSession.SessionOptions()
                try {
                    // Preserve the validated math and low-memory CPU execution settings.
                    // 0.23 device probes: arena + memory pattern was killed for LOW_MEMORY
                    // on its second inference; extended graph fusion changed output values.
                    options.setIntraOpNumThreads(intraOpThreads)
                    options.setInterOpNumThreads(1)
                    options.setCPUArenaAllocator(false)
                    options.setMemoryPatternOptimization(false)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    loaded = LoadedSession(options, env.createSession(File(root,modelName).absolutePath, options))
                } catch (t: Throwable) { options.close(); throw t }
            }
            val session = checkNotNull(loaded).session
            val ready = SystemClock.elapsedRealtime()
            coroutineContext.ensureActive()
            val original = BitmapFactory.decodeFile(photo.path) ?: error("照片解码失败")
            val rgb = try {
                val pixels = IntArray(original.width*original.height)
                original.getPixels(pixels,0,original.width,0,0,original.width,original.height)
                SharpImageUseCase.resizeAlignCorners(pixels,original.width,original.height,resolution)
            } finally { original.recycle() }
            OnnxTensor.createTensor(env,FloatBuffer.wrap(rgb),longArrayOf(1,3,resolution.toLong(),resolution.toLong())).use { image ->
                OnnxTensor.createTensor(env,FloatBuffer.wrap(floatArrayOf(photo.fx/photo.width)),longArrayOf(1)).use { focal ->
                    coroutineContext.ensureActive()
                    progress("正在端上重建空间照片…")
                    val before = SystemClock.elapsedRealtime()
                    session.run(mapOf("image" to image,"disparity_factor" to focal)).use { result ->
                        val inferred = SystemClock.elapsedRealtime()
                        coroutineContext.ensureActive()
                        fun tensor(name: String): FloatArray {
                            val t = result.get(name).orElseThrow { IllegalStateException("缺少模型输出$name") } as OnnxTensor
                            val b = t.floatBuffer ?: error("$name 不是 FLOAT32 输出")
                            return FloatArray(b.remaining()).also { b.get(it) }
                        }
                        val output = GaussianOutput(tensor("means"),tensor("scales"),tensor("quaternions"),tensor("colors"),tensor("opacities"))
                        val ended = SystemClock.elapsedRealtime()
                        lastTiming = PredictionTiming(reused,prepared-started,ready-prepared,before-ready,inferred-before,ended-inferred,ended-started)
                        Log.i("SpatialPhoto","INFERENCE_DONE count=${output.count} backend=CPU timing=$lastTiming")
                        output
                    }
                }
            }
        } } finally {
            // Bound retained model memory. No UI-hidden callback: capture switches containers.
            idleRelease = cleanupScope.launch {
                delay(idleTimeoutMillis)
                mutex.withLock {
                    val old = loaded; loaded = null
                    old?.close()
                }
            }
        }
    }
}
