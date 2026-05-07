package com.newoether.agora.api

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File

object LocalEmbeddingEngine {
    private const val TAG = "LocalEmbedding"

    fun isModelReady(modelPath: String): Boolean {
        return modelPath.isNotBlank() && File(modelPath).exists() && File(modelPath).length() > 0
    }

    fun computeEmbedding(text: String, modelPath: String): FloatArray? {
        var interpreter: Interpreter? = null
        return try {
            interpreter = Interpreter(File(modelPath))
            val outputShape = interpreter.getOutputTensor(0).shape()
            val dim = outputShape.last()
            val output = Array(1) { FloatArray(dim) }
            interpreter.run(arrayOf(text), output)
            interpreter.close()
            interpreter = null
            output[0]
        } catch (e: Exception) {
            Log.e(TAG, "Local embedding inference failed", e)
            null
        } finally {
            interpreter?.close()
        }
    }
}
