package com.newoether.agora.api

import android.util.Log
import java.io.File

object LlamaEngine {
    private const val TAG = "LlamaEngine"

    init {
        try {
            System.loadLibrary("agora_llama")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load agora_llama native library", e)
        }
    }

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeComputeEmbedding(handle: Long, text: String): FloatArray?
    private external fun nativeGetEmbeddingDim(handle: Long): Int

    fun isModelReady(modelPath: String): Boolean {
        return modelPath.isNotBlank() && File(modelPath).exists() && File(modelPath).length() > 0
    }

    fun computeEmbedding(text: String, modelPath: String): FloatArray? {
        val handle = nativeLoadModel(modelPath)
        if (handle == 0L) {
            Log.e(TAG, "Failed to load model: $modelPath")
            return null
        }
        return try {
            nativeComputeEmbedding(handle, text)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding computation failed", e)
            null
        } finally {
            nativeFreeModel(handle)
        }
    }
}
