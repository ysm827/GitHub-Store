package zed.rainxch.auth.presentation

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface AuthDeepLinkEvent {
    data class Handoff(
        val handoffId: String,
        val state: String,
    ) : AuthDeepLinkEvent

    data class Error(
        val reason: String,
        val state: String,
    ) : AuthDeepLinkEvent
}

object AuthDeepLinkBus {
    private val _events =
        MutableSharedFlow<AuthDeepLinkEvent>(
            replay = 1,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<AuthDeepLinkEvent> = _events.asSharedFlow()

    fun publish(event: AuthDeepLinkEvent) {
        _events.tryEmit(event)
    }

    fun resetReplay() {
        _events.resetReplayCache()
    }
}
