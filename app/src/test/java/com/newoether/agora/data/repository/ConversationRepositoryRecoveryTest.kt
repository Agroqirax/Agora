package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryRecoveryTest {
    @Test
    fun transientRecoveryFailureRetriesBeforeOpeningGenerationBarrier() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.recoverOrphanedRuns(any()) } throws
            IllegalStateException("database temporarily busy") andThen 1
        val repository = ConversationRepository(dao)

        repository.ensureRunRecovery()
        // The completed barrier is process-idempotent.
        repository.ensureRunRecovery()

        coVerify(exactly = 2) { dao.recoverOrphanedRuns(any()) }
    }
}
