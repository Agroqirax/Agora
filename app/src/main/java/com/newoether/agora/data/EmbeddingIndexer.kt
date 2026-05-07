package com.newoether.agora.data

import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.EmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EmbeddingIndexer {

    suspend fun indexMessage(
        messageId: String,
        text: String,
        apiKey: String,
        model: String,
        baseUrl: String,
        modelId: String,
        chatDao: ChatDao
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val toEmbed = text.take(8000) // truncate for embedding models
            val embedding = EmbeddingClient.computeEmbedding(toEmbed, apiKey, model, baseUrl) ?: return@withContext false
            chatDao.upsertEmbedding(EmbeddingEntity(
                messageId = messageId,
                modelId = modelId,
                embedding = floatsToBytes(embedding),
                chunkText = toEmbed.take(500),
                dimension = embedding.size
            ))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun floatsToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (f in floats) buffer.putFloat(f)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buffer.float
        return floats
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
