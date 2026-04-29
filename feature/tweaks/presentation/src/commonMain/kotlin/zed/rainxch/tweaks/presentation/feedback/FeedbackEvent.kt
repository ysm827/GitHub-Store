package zed.rainxch.tweaks.presentation.feedback

import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel

sealed interface FeedbackEvent {
    /** Emitted after `BrowserHelper.openUrl` returned without invoking
     *  `onFailure`. The host (TweaksRoot) collapses the sheet and
     *  shows a per-channel success snackbar. */
    data class OnSent(val channel: FeedbackChannel) : FeedbackEvent

    data class OnSendError(val message: String) : FeedbackEvent
}
