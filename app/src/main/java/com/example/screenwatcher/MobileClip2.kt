package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * MobileCLIP2-S2 vision encoder chạy ONNX Runtime hoàn toàn trên thiết bị.
 * File model được tải lúc chuẩn bị mã nguồn/build và đóng gói trong APK assets.
 * Không có API mạng và không gửi ảnh ra ngoài.
 */
object MobileClip2 {
    private const val MODEL_ASSET = "mobileclip2_s2_vision.onnx"
    private const val INPUT_SIZE = 256
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized private fun getSession(context: Context): Pair<OrtEnvironment, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val s = session ?: run {
            val model = File(context.filesDir, MODEL_ASSET)
            if (!model.exists()) {
                context.assets.open(MODEL_ASSET).use { input -> model.outputStream().use { output -> input.copyTo(output) } }
            }
            e.createSession(model.absolutePath, OrtSession.SessionOptions()).also { session = it }
        }
        return e to s
    }

    /** Trả về embedding L2-normalized 512 chiều để so khớp cosine. */
    fun embed(context: Context, bitmap: Bitmap): FloatArray {
        require(!bitmap.isRecycled && bitmap.width >= 8 && bitmap.height >= 8) { "Ảnh đầu vào không hợp lệ" }
        val (e, s) = getSession(context)
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // ONNX export của MobileCLIP2 dùng pixel_values NCHW, RGB [0..1], mean=0/std=1.
        val data = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val px = pixels[i]
            data[i] = ((px shr 16) and 255) / 255f
            data[plane + i] = ((px shr 8) and 255) / 255f
            data[2 * plane + i] = (px and 255) / 255f
        }
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
        tensor.use { input ->
            val inputName = s.inputNames.firstOrNull() ?: "pixel_values"
            s.run(mapOf(inputName to input)).use { result ->
                val output = result[0].value
                val raw = when (output) {
                    is Array<*> -> (output[0] as? FloatArray) ?: error("MobileCLIP2 output không đúng dạng")
                    is FloatArray -> output
                    else -> error("Kiểu output MobileCLIP2 không hỗ trợ: ${output?.javaClass}")
                }
                var norm2 = 0.0
                raw.forEach { norm2 += it.toDouble() * it.toDouble() }
                val norm = kotlin.math.sqrt(norm2).toFloat().coerceAtLeast(1e-12f)
                return FloatArray(raw.size) { raw[it] / norm }
            }
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }

    @Synchronized fun close() {
        runCatching { session?.close() }
        session = null
        env = null
    }
}
