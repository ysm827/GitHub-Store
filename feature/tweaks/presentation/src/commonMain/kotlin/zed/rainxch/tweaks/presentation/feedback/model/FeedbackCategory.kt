package zed.rainxch.tweaks.presentation.feedback.model

import org.jetbrains.compose.resources.StringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_category_bug
import zed.rainxch.githubstore.core.presentation.res.feedback_category_change
import zed.rainxch.githubstore.core.presentation.res.feedback_category_feature
import zed.rainxch.githubstore.core.presentation.res.feedback_category_other

enum class FeedbackCategory(
    val label: StringResource,
    val githubLabel: String,
) {
    BUG(Res.string.feedback_category_bug, "type:bug"),
    FEATURE_REQUEST(Res.string.feedback_category_feature, "type:feature"),
    CHANGE_REQUEST(Res.string.feedback_category_change, "type:change"),
    OTHER(Res.string.feedback_category_other, "type:other"),
}
