package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory

class ChatViewModelFactory(
    private val application: Application,
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao,
    private val memoryManager: MemoryManager,
    private val context: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val autoBackupManager: AutoBackupManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                application, settingsManager, chatDao, memoryManager, context, sandboxFactory,
                autoBackupManager, conversationRepository, settingsRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
