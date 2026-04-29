package zed.rainxch.tweaks.presentation.feedback.model

import org.jetbrains.compose.resources.StringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_auth
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_details
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_install_update
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_other
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_performance
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_search
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_translation
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_ui

enum class FeedbackTopic(
    val label: StringResource,
    val githubLabel: String,
) {
    INSTALL_UPDATE(Res.string.feedback_topic_install_update, "area:install"),
    SEARCH_DISCOVERY(Res.string.feedback_topic_search, "area:search"),
    REPO_DETAILS(Res.string.feedback_topic_details, "area:details"),
    AUTH_ACCOUNT(Res.string.feedback_topic_auth, "area:auth"),
    UI_UX(Res.string.feedback_topic_ui, "area:ui"),
    TRANSLATION(Res.string.feedback_topic_translation, "area:translation"),
    PERFORMANCE(Res.string.feedback_topic_performance, "area:performance"),
    OTHER(Res.string.feedback_topic_other, "area:other"),
}
