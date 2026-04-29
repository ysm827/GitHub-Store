package zed.rainxch.tweaks.presentation.feedback

import zed.rainxch.tweaks.presentation.feedback.model.DiagnosticsInfo
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackCategory
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackTopic

data class FeedbackState(
    val category: FeedbackCategory = FeedbackCategory.BUG,
    val topic: FeedbackTopic = FeedbackTopic.OTHER,
    val title: String = "",
    val description: String = "",
    val stepsToReproduce: String = "",
    val expectedActual: String = "",
    val useCase: String = "",
    val proposedSolution: String = "",
    val currentBehaviour: String = "",
    val desiredBehaviour: String = "",
    val attachDiagnostics: Boolean = true,
    val diagnostics: DiagnosticsInfo? = null,
    val isSending: Boolean = false,
) {
    val canSend: Boolean
        get() = title.isNotBlank() && description.isNotBlank() && !isSending
}
