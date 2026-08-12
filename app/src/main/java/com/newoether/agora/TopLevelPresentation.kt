package com.newoether.agora

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class TopLevelPresentation {
    CHAT,
    SETTINGS,
    TASKS,
    MEDIA_PREVIEW,
    TEXT_PREVIEW,
    WIDGET_PREVIEW,
}

/** Single main-thread owner for the top-level surface currently covering Chat. */
@Stable
internal class TopLevelPresentationState(
    initialOwner: TopLevelPresentation = TopLevelPresentation.CHAT,
) {
    var owner by mutableStateOf(initialOwner)
        private set

    fun present(presentation: TopLevelPresentation) {
        require(presentation != TopLevelPresentation.CHAT)
        owner = presentation
    }

    /** A stale exiting surface cannot return ownership after a newer surface was presented. */
    fun release(presentation: TopLevelPresentation): Boolean {
        if (owner != presentation) return false
        owner = TopLevelPresentation.CHAT
        return true
    }
}
