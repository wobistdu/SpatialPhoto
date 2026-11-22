package com.example.spatialphoto.domain.usecase

import java.util.PriorityQueue
import kotlin.math.*

/** Photo-only sampling. Small leaves retain text/edges; flat areas use larger footprints. */
internal object AdaptivePhotoSampling {
    const val WIDTH = 960
    const val HEIGHT = 720
    private const val FRONT_BUDGET = 230400

    data class Cell(val x: Int, val y: Int, val size: Int, val error: Double)
    data class Samples(val rgb: FloatArray, val cells: List<Cell>)

    fun prepare(pixels: IntArray, width: Int, height: Int, checkActive: () -> Unit): Samples {
        val rgb = resize(pixels, width, height, checkActive)
        fun cell(x: Int, y: Int, size: Int): Cell {
            var r = 0.0; var g = 0.0; var b = 0.0; var square = 0.0
            for (dy in 0 until size) for (dx in 0 until size) {
                val i = ((y + dy) * WIDTH + x + dx) * 3
                val cr = rgb[i].toDouble(); val cg = rgb[i + 1].toDouble(); val cb = rgb[i + 2].toDouble()
                r += cr; g += cg; b += cb
                square += cr * cr + cg * cg + cb * cb
            }
            return Cell(x, y, size, max(0.0, square - (r * r + g * g + b * b) / (size * size)))
        }
        val queue = PriorityQueue<Cell>(compareByDescending<Cell> { it.error }.thenBy { it.y }.thenBy { it.x })
        val sizes = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT step 4) {
            checkActive()
            for (x in 0 until WIDTH step 4) {
                sizes[y * WIDTH + x] = 4
                queue.add(cell(x, y, 4))
            }
        }
        var count = WIDTH * HEIGHT / 16
        while (count + 3 <= FRONT_BUDGET && queue.isNotEmpty()) {
            if (count % 1024 < 3) checkActive()
            val parent = queue.remove()
            if (parent.error < 1e-8) break
            val size = parent.size / 2
            for (dy in 0..1) for (dx in 0..1) {
                val x = parent.x + dx * size; val y = parent.y + dy * size
                sizes[y * WIDTH + x] = size
                if (size > 1) queue.add(cell(x, y, size))
            }
            count += 3
        }
        val cells = ArrayList<Cell>(count)
        for (y in 0 until HEIGHT) {
            checkActive()
            for (x in 0 until WIDTH) {
                val size = sizes[y * WIDTH + x]
                if (size > 0) cells.add(Cell(x, y, size, 0.0))
            }
        }
        check(cells.size == count)
        return Samples(rgb, cells)
    }

    /** Separable antialiased Lanczos-3 in sRGB; bounded temporary storage, no bitmap ownership. */
    fun resize(pixels: IntArray, width: Int, height: Int, checkActive: () -> Unit): FloatArray {
        data class Kernel(val first: Int, val weights: DoubleArray)
        fun kernels(source: Int, target: Int): Array<Kernel> {
            val scale = max(1.0, source.toDouble() / target)
            fun sinc(x: Double) = if (abs(x) < 1e-10) 1.0 else sin(PI * x) / (PI * x)
            return Array(target) { index ->
                val center = (index + .5) * source / target - .5
                val first = ceil(center - 3 * scale).toInt().coerceAtLeast(0)
                val last = floor(center + 3 * scale).toInt().coerceAtMost(source - 1)
                val weights = DoubleArray(last - first + 1) { k ->
                    val d = (first + k - center) / scale
                    if (abs(d) >= 3) 0.0 else sinc(d) * sinc(d / 3)
                }
                val total = weights.sum()
                weights.indices.forEach { weights[it] /= total }
                Kernel(first, weights)
            }
        }
        val xs = kernels(width, WIDTH); val ys = kernels(height, HEIGHT)
        val horizontal = FloatArray(WIDTH * height * 3)
        for (y in 0 until height) {
            checkActive()
            for (x in 0 until WIDTH) {
                val kernel = xs[x]
                var r = 0.0; var g = 0.0; var b = 0.0
                for (k in kernel.weights.indices) {
                    val pixel = pixels[y * width + kernel.first + k]; val weight = kernel.weights[k] / 255
                    r += ((pixel ushr 16) and 255) * weight
                    g += ((pixel ushr 8) and 255) * weight
                    b += (pixel and 255) * weight
                }
                val i = (y * WIDTH + x) * 3
                horizontal[i] = r.toFloat(); horizontal[i + 1] = g.toFloat(); horizontal[i + 2] = b.toFloat()
            }
        }
        val output = FloatArray(WIDTH * HEIGHT * 3)
        for (y in 0 until HEIGHT) {
            checkActive()
            val kernel = ys[y]
            for (x in 0 until WIDTH) for (c in 0..2) {
                var sum = 0.0
                for (k in kernel.weights.indices) sum += horizontal[((kernel.first + k) * WIDTH + x) * 3 + c] * kernel.weights[k]
                output[(y * WIDTH + x) * 3 + c] = sum.coerceIn(0.0, 1.0).toFloat()
            }
        }
        return output
    }
}
