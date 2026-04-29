# In-App Feedback Sheet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app feedback bottom sheet (Tweaks → About → "Send feedback") that lets users compose a structured report and dispatch it as either a pre-filled email to `hello@github-store.org` or a pre-filled GitHub issue on `OpenHub-Store/GitHub-Store`.

**Architecture:** New `feedback/` sub-package under `feature/tweaks/presentation/commonMain` containing its own MVI triad (`FeedbackViewModel` / `State` / `Action` / `Event`), a pure URL/body composer, and small Compose components. `TweaksViewModel` owns only the open/close flag.

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Multiplatform, Material 3 (`ModalBottomSheet`, `FilterChip`, `OutlinedTextField`), Koin DI (`koinViewModel`), Kotlinx Coroutines (`StateFlow` / `Channel`), `BrowserHelper` (existing core/domain interface).

**Spec:** [`docs/superpowers/specs/2026-04-29-tweaks-feedback-design.md`](../specs/2026-04-29-tweaks-feedback-design.md)

**Note on testing:** This codebase has no test source sets, no JUnit/kotlin.test wiring, and no precedent for unit tests in feature presentation modules. Adding test infrastructure is out of scope for this feature (the user explicitly dropped the testing section from the spec). The plan therefore omits TDD steps and prioritises careful, incremental implementation with manual smoke tests against `./gradlew :composeApp:assembleDebug` and `./gradlew :composeApp:run`.

**Commit conventions:** Match existing repo style — single short imperative sentence (e.g. `Add in-app feedback bottom sheet`). No `Co-Authored-By` trailers. One commit per task.

---

## Task 1 — Add `getOsVersion()` and `getSystemLocaleTag()` expect/actual

**Files:**
- Modify: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/Platform.kt`
- Modify: `core/domain/src/androidMain/kotlin/zed/rainxch/core/domain/Platform.android.kt`
- Modify: `core/domain/src/jvmMain/kotlin/zed/rainxch/core/domain/Platform.jvm.kt`

`Platform.kt` currently only declares `expect fun getPlatform(): Platform`. The diagnostics block needs OS version and locale tag too.

- [ ] **Step 1: Add expect declarations to `Platform.kt`**

Replace the file contents with:

```kotlin
package zed.rainxch.core.domain

import zed.rainxch.core.domain.model.Platform

expect fun getPlatform(): Platform

expect fun getOsVersion(): String

expect fun getSystemLocaleTag(): String
```

- [ ] **Step 2: Add Android actuals to `Platform.android.kt`**

Replace the file contents with:

```kotlin
package zed.rainxch.core.domain

import android.os.Build
import java.util.Locale
import zed.rainxch.core.domain.model.Platform

actual fun getPlatform(): Platform = Platform.ANDROID

actual fun getOsVersion(): String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

actual fun getSystemLocaleTag(): String =
    Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "und"
```

- [ ] **Step 3: Add JVM actuals to `Platform.jvm.kt`**

Replace the file contents with:

```kotlin
package zed.rainxch.core.domain

import java.util.Locale
import zed.rainxch.core.domain.model.Platform

actual fun getPlatform(): Platform =
    when {
        System.getProperty("os.name").lowercase().contains("win") -> Platform.WINDOWS
        System.getProperty("os.name").lowercase().contains("mac") -> Platform.MACOS
        else -> Platform.LINUX
    }

actual fun getOsVersion(): String = System.getProperty("os.version") ?: "unknown"

actual fun getSystemLocaleTag(): String =
    Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "und"
```

- [ ] **Step 4: Verify the project still compiles**

Run: `./gradlew :core:domain:compileKotlinJvm :core:domain:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/Platform.kt \
        core/domain/src/androidMain/kotlin/zed/rainxch/core/domain/Platform.android.kt \
        core/domain/src/jvmMain/kotlin/zed/rainxch/core/domain/Platform.jvm.kt
git commit -m "Expose OS version and system locale tag from core/domain Platform helpers"
```

---

## Task 2 — Add English string resources

**Files:**
- Modify: `core/presentation/src/commonMain/composeResources/values/strings.xml`

Adds the ~28 keys the feedback sheet needs. English values only — non-English locale files are left untouched in this task; they fall back to the English values automatically and translators backfill them on the next localization pass (this matches how other tweaks features were rolled out).

- [ ] **Step 1: Append a new section at the end of `<resources>`**

Open `core/presentation/src/commonMain/composeResources/values/strings.xml` and append the following block immediately before the closing `</resources>` tag:

```xml
    <!-- Tweaks feature - Feedback -->
    <string name="feedback_send">Send feedback</string>
    <string name="feedback_title">Send feedback</string>
    <string name="feedback_close">Close</string>

    <!-- Feedback - Category -->
    <string name="feedback_category_label">Category</string>
    <string name="feedback_category_bug">Bug</string>
    <string name="feedback_category_feature">Feature request</string>
    <string name="feedback_category_change">Change request</string>
    <string name="feedback_category_other">Other</string>

    <!-- Feedback - Topic -->
    <string name="feedback_topic_label">Topic</string>
    <string name="feedback_topic_install_update">Install / Update</string>
    <string name="feedback_topic_search">Search &amp; Discovery</string>
    <string name="feedback_topic_details">Repo details</string>
    <string name="feedback_topic_auth">Auth &amp; Account</string>
    <string name="feedback_topic_ui">UI / UX</string>
    <string name="feedback_topic_translation">Translation / Language</string>
    <string name="feedback_topic_performance">Performance</string>
    <string name="feedback_topic_other">Other</string>

    <!-- Feedback - Form fields -->
    <string name="feedback_field_title">Title</string>
    <string name="feedback_field_description">Description</string>
    <string name="feedback_field_steps">Steps to reproduce</string>
    <string name="feedback_field_expected_actual">Expected vs actual</string>
    <string name="feedback_field_use_case">Use case</string>
    <string name="feedback_field_proposed_solution">Proposed solution</string>
    <string name="feedback_field_current_behaviour">Current behaviour</string>
    <string name="feedback_field_desired_behaviour">Desired behaviour</string>

    <!-- Feedback - Diagnostics -->
    <string name="feedback_diagnostics_header">Diagnostics</string>
    <string name="feedback_diagnostics_include">Include diagnostics</string>

    <!-- Feedback - Send actions -->
    <string name="feedback_send_via_email">Send Email</string>
    <string name="feedback_send_via_github">Open as GitHub Issue</string>
    <string name="feedback_send_success_email">Thanks — opening your mail client.</string>
    <string name="feedback_send_success_github">Thanks — opening your browser.</string>
    <string name="feedback_send_error">Couldn\'t open feedback channel: %1$s</string>
```

Use exactly two-space indentation matching the rest of the file. The `&amp;` entity is required inside XML attribute values for `&`.

- [ ] **Step 2: Verify resource generation succeeds**

Run: `./gradlew :core:presentation:generateComposeResClass`
Expected: BUILD SUCCESSFUL. This generates the `Res.string.feedback_*` accessors used in subsequent tasks.

- [ ] **Step 3: Commit**

```bash
git add core/presentation/src/commonMain/composeResources/values/strings.xml
git commit -m "Add string resources for in-app feedback bottom sheet"
```

---

## Task 3 — Define enums and `DiagnosticsInfo`

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/model/FeedbackCategory.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/model/FeedbackTopic.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/model/FeedbackChannel.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/model/DiagnosticsInfo.kt`

- [ ] **Step 1: Create `FeedbackCategory.kt`**

```kotlin
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
```

- [ ] **Step 2: Create `FeedbackTopic.kt`**

```kotlin
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
```

- [ ] **Step 3: Create `FeedbackChannel.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.model

enum class FeedbackChannel { EMAIL, GITHUB }
```

- [ ] **Step 4: Create `DiagnosticsInfo.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.model

data class DiagnosticsInfo(
    val appVersion: String,
    val platform: String,
    val osVersion: String,
    val locale: String,
    val installerType: String?,
    val githubUsername: String?,
)
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/model/
git commit -m "Add feedback category, topic, channel, and diagnostics model types"
```

---

## Task 4 — Define `FeedbackState`, `FeedbackAction`, `FeedbackEvent`

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackState.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackAction.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackEvent.kt`

- [ ] **Step 1: Create `FeedbackState.kt`**

```kotlin
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
```

- [ ] **Step 2: Create `FeedbackAction.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback

import zed.rainxch.tweaks.presentation.feedback.model.FeedbackCategory
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackTopic

sealed interface FeedbackAction {
    data class OnCategoryChange(val category: FeedbackCategory) : FeedbackAction

    data class OnTopicChange(val topic: FeedbackTopic) : FeedbackAction

    data class OnTitleChange(val value: String) : FeedbackAction

    data class OnDescriptionChange(val value: String) : FeedbackAction

    data class OnStepsToReproduceChange(val value: String) : FeedbackAction

    data class OnExpectedActualChange(val value: String) : FeedbackAction

    data class OnUseCaseChange(val value: String) : FeedbackAction

    data class OnProposedSolutionChange(val value: String) : FeedbackAction

    data class OnCurrentBehaviourChange(val value: String) : FeedbackAction

    data class OnDesiredBehaviourChange(val value: String) : FeedbackAction

    data object OnAttachDiagnosticsToggle : FeedbackAction

    data object OnSendViaEmail : FeedbackAction

    data object OnSendViaGithub : FeedbackAction

    data object OnDismiss : FeedbackAction
}
```

- [ ] **Step 3: Create `FeedbackEvent.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback

import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel

sealed interface FeedbackEvent {
    /** Emitted after `BrowserHelper.openUrl` returned without invoking
     *  `onFailure`. The host (TweaksRoot) collapses the sheet and
     *  shows a per-channel success snackbar. */
    data class OnSent(val channel: FeedbackChannel) : FeedbackEvent

    data class OnSendError(val message: String) : FeedbackEvent
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackState.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackAction.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackEvent.kt
git commit -m "Add FeedbackState, FeedbackAction, FeedbackEvent for the feedback sheet"
```

---

## Task 5 — Implement `FeedbackComposer`

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/util/FeedbackComposer.kt`

Pure object that builds the markdown body and final URL for both channels. Channel-aware so `EMAIL` never receives the GitHub username. Caps the raw body at 7,500 chars before URL encoding.

- [ ] **Step 1: Create `FeedbackComposer.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.util

import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter
import zed.rainxch.tweaks.presentation.feedback.FeedbackState
import zed.rainxch.tweaks.presentation.feedback.model.DiagnosticsInfo
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackCategory
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel

object FeedbackComposer {
    const val FEEDBACK_EMAIL = "hello@github-store.org"
    const val FEEDBACK_REPO = "OpenHub-Store/GitHub-Store"
    const val BODY_MAX_CHARS = 7_500

    fun composeUrl(state: FeedbackState, channel: FeedbackChannel): String {
        val title = state.title.trim()
        val body = composeBody(state, channel)
        return when (channel) {
            FeedbackChannel.EMAIL -> buildMailto(title, body)
            FeedbackChannel.GITHUB -> buildGithubIssueUrl(title, body, state)
        }
    }

    fun composeBody(state: FeedbackState, channel: FeedbackChannel): String {
        val builder = StringBuilder()

        builder.appendSection("Description", state.description)

        when (state.category) {
            FeedbackCategory.BUG -> {
                builder.appendSection("Steps to reproduce", state.stepsToReproduce)
                builder.appendSection("Expected vs actual", state.expectedActual)
            }
            FeedbackCategory.FEATURE_REQUEST -> {
                builder.appendSection("Use case", state.useCase)
                builder.appendSection("Proposed solution", state.proposedSolution)
            }
            FeedbackCategory.CHANGE_REQUEST -> {
                builder.appendSection("Current behaviour", state.currentBehaviour)
                builder.appendSection("Desired behaviour", state.desiredBehaviour)
            }
            FeedbackCategory.OTHER -> { /* no extra fields */ }
        }

        if (state.attachDiagnostics) {
            state.diagnostics?.let { d ->
                builder.append("\n\n---\n**Diagnostics**\n")
                builder.append("- App: GitHub Store v").append(d.appVersion).append('\n')
                builder.append("- Platform: ").append(d.platform).append(' ').append(d.osVersion).append('\n')
                builder.append("- Locale: ").append(d.locale).append('\n')
                d.installerType?.let { builder.append("- Installer: ").append(it).append('\n') }
                if (channel == FeedbackChannel.GITHUB) {
                    d.githubUsername?.let { builder.append("- GitHub user: @").append(it).append('\n') }
                }
            }
        }

        return builder.toString().truncateToCap()
    }

    private fun StringBuilder.appendSection(title: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        if (isNotEmpty()) append("\n\n")
        append("## ").append(title).append('\n').append(trimmed)
    }

    private fun String.truncateToCap(): String =
        if (length <= BODY_MAX_CHARS) this
        else substring(0, BODY_MAX_CHARS) + "\n\n…[truncated]"

    private fun buildMailto(title: String, body: String): String {
        val subject = title.encodeURLParameter()
        val encodedBody = body.encodeURLParameter()
        return "mailto:$FEEDBACK_EMAIL?subject=$subject&body=$encodedBody"
    }

    private fun buildGithubIssueUrl(title: String, body: String, state: FeedbackState): String {
        val labels = listOf(state.category.githubLabel, state.topic.githubLabel).joinToString(",")
        return URLBuilder("https://github.com/$FEEDBACK_REPO/issues/new").apply {
            parameters.append("title", title)
            parameters.append("body", body)
            parameters.append("labels", labels)
        }.buildString()
    }
}
```

The Ktor `URLBuilder.parameters.append` already URL-encodes values, so we pass them raw there. For the `mailto:` scheme `URLBuilder` would change the path semantics, so we hand-encode via `encodeURLParameter()` and concatenate.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke check via the IDE / scratch**

Open `FeedbackComposer.kt` and (mentally, or in a temporary `main`) trace:

```kotlin
val state = FeedbackState(
    category = FeedbackCategory.BUG,
    title = "Search misses xyz",
    description = "I can't find Obtanium when I search for it",
    stepsToReproduce = "1. Open search\n2. Type 'obtanium'",
    diagnostics = DiagnosticsInfo(
        appVersion = "1.6.2 (13)",
        platform = "Android",
        osVersion = "14 (API 34)",
        locale = "en-US",
        installerType = "Shizuku (READY)",
        githubUsername = "rainxchzed",
    ),
)
val url = FeedbackComposer.composeUrl(state, FeedbackChannel.GITHUB)
```

Expected `url` starts with `https://github.com/OpenHub-Store/GitHub-Store/issues/new?title=Search+misses+xyz&body=...&labels=type%3Abug%2Carea%3Aother`.
Expected `composeBody(..., FeedbackChannel.EMAIL)` does **not** contain `GitHub user: @rainxchzed`.

You don't need to check this in code — eyeball the implementation against these expectations.

- [ ] **Step 4: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/util/FeedbackComposer.kt
git commit -m "Add FeedbackComposer for mailto and GitHub issue URL assembly"
```

---

## Task 6 — Implement `FeedbackViewModel`

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackViewModel.kt`

Holds the form state, gathers diagnostics on init, dispatches sends via `BrowserHelper`, resets state on dismiss.

- [ ] **Step 1: Create `FeedbackViewModel.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zed.rainxch.core.domain.getOsVersion
import zed.rainxch.core.domain.getPlatform
import zed.rainxch.core.domain.getSystemLocaleTag
import zed.rainxch.core.domain.model.InstallerType
import zed.rainxch.core.domain.model.Platform
import zed.rainxch.core.domain.repository.TweaksRepository
import zed.rainxch.core.domain.utils.BrowserHelper
import zed.rainxch.profile.domain.repository.ProfileRepository
import zed.rainxch.tweaks.presentation.feedback.model.DiagnosticsInfo
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel
import zed.rainxch.tweaks.presentation.feedback.util.FeedbackComposer

class FeedbackViewModel(
    private val browserHelper: BrowserHelper,
    private val tweaksRepository: TweaksRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedbackState())
    val state = _state.asStateFlow()

    private val _events = Channel<FeedbackEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(diagnostics = collectDiagnostics()) }
        }
    }

    fun onAction(action: FeedbackAction) {
        when (action) {
            is FeedbackAction.OnCategoryChange ->
                _state.update { it.copy(category = action.category) }
            is FeedbackAction.OnTopicChange ->
                _state.update { it.copy(topic = action.topic) }
            is FeedbackAction.OnTitleChange ->
                _state.update { it.copy(title = action.value) }
            is FeedbackAction.OnDescriptionChange ->
                _state.update { it.copy(description = action.value) }
            is FeedbackAction.OnStepsToReproduceChange ->
                _state.update { it.copy(stepsToReproduce = action.value) }
            is FeedbackAction.OnExpectedActualChange ->
                _state.update { it.copy(expectedActual = action.value) }
            is FeedbackAction.OnUseCaseChange ->
                _state.update { it.copy(useCase = action.value) }
            is FeedbackAction.OnProposedSolutionChange ->
                _state.update { it.copy(proposedSolution = action.value) }
            is FeedbackAction.OnCurrentBehaviourChange ->
                _state.update { it.copy(currentBehaviour = action.value) }
            is FeedbackAction.OnDesiredBehaviourChange ->
                _state.update { it.copy(desiredBehaviour = action.value) }
            FeedbackAction.OnAttachDiagnosticsToggle ->
                _state.update { it.copy(attachDiagnostics = !it.attachDiagnostics) }
            FeedbackAction.OnSendViaEmail -> send(FeedbackChannel.EMAIL)
            FeedbackAction.OnSendViaGithub -> send(FeedbackChannel.GITHUB)
            FeedbackAction.OnDismiss -> resetForm()
        }
    }

    private fun send(channel: FeedbackChannel) {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(isSending = true) }
        viewModelScope.launch {
            var failed = false
            val url = FeedbackComposer.composeUrl(current, channel)
            browserHelper.openUrl(url) { error ->
                failed = true
                viewModelScope.launch {
                    _events.send(FeedbackEvent.OnSendError(error))
                }
            }
            // Hold the disabled state briefly so the user sees the
            // buttons disable and can't double-tap; long enough to
            // also let any synchronous onFailure invocation arrive.
            delay(250)
            _state.update { it.copy(isSending = false) }
            if (!failed) {
                _events.send(FeedbackEvent.OnSent(channel))
            }
        }
    }

    private fun resetForm() {
        // Preserve already-collected diagnostics so we don't re-query
        // repositories when the sheet reopens.
        _state.update { previous ->
            FeedbackState(diagnostics = previous.diagnostics)
        }
    }

    private suspend fun collectDiagnostics(): DiagnosticsInfo {
        val installerType = tweaksRepository.getInstallerType().first()
        val platform = getPlatform()
        val installerString =
            if (platform == Platform.ANDROID) {
                when (installerType) {
                    InstallerType.DEFAULT -> "Default"
                    InstallerType.SHIZUKU -> "Shizuku"
                }
            } else {
                null
            }
        val user = profileRepository.getUser().firstOrNull()
        val appLanguage = tweaksRepository.getAppLanguage().firstOrNull()
        return DiagnosticsInfo(
            appVersion = profileRepository.getVersionName(),
            platform = platform.displayName(),
            osVersion = getOsVersion(),
            locale = appLanguage ?: getSystemLocaleTag(),
            installerType = installerString,
            githubUsername = user?.login,
        )
    }

    private fun Platform.displayName(): String =
        when (this) {
            Platform.ANDROID -> "Android"
            Platform.WINDOWS -> "Windows"
            Platform.MACOS -> "macOS"
            Platform.LINUX -> "Linux"
        }
}
```

Note: `UserProfile.login` is the GitHub handle. If this property is named differently in your codebase, replace `user?.login` with the correct accessor.

- [ ] **Step 2: Verify `UserProfile.login` exists**

Run: `grep -n "class UserProfile\|val login" feature/profile/domain/src/commonMain/kotlin/zed/rainxch/profile/domain/model/UserProfile.kt`
Expected: a `val login: String` (or similar) property is present. If it's named `username` or `name`, change `user?.login` in `collectDiagnostics()` accordingly.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackViewModel.kt
git commit -m "Add FeedbackViewModel with diagnostics gathering and send dispatch"
```

---

## Task 7 — Register `FeedbackViewModel` in Koin

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`

- [ ] **Step 1: Add import and registration**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`.

Add this import alphabetically with the other tweaks imports (right above `import zed.rainxch.tweaks.presentation.TweaksViewModel`):

```kotlin
import zed.rainxch.tweaks.presentation.feedback.FeedbackViewModel
```

In the `module { … }` block, add the registration immediately after `viewModelOf(::TweaksViewModel)`:

```kotlin
        viewModelOf(::FeedbackViewModel)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt
git commit -m "Register FeedbackViewModel in the Koin viewModels module"
```

---

## Task 8 — Build `CategorySelector` composable

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/CategorySelector.kt`

Radio group of 4 options. Uses `selectableGroup` + `selectable(role = Role.RadioButton)` for accessibility (this is the same pattern `Installation.kt` uses for the installer picker).

- [ ] **Step 1: Create `CategorySelector.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_category_label
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackCategory

@Composable
fun CategorySelector(
    selected: FeedbackCategory,
    onSelected: (FeedbackCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.feedback_category_label),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FeedbackCategory.entries.forEach { category ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = category == selected,
                            onClick = { onSelected(category) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = category == selected,
                        onClick = null,
                    )
                    Text(
                        text = stringResource(category.label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/CategorySelector.kt
git commit -m "Add CategorySelector radio group for the feedback sheet"
```

---

## Task 9 — Build `TopicSelector` composable

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/TopicSelector.kt`

Single-select `FilterChip` flow row.

- [ ] **Step 1: Create `TopicSelector.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_topic_label
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackTopic

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicSelector(
    selected: FeedbackTopic,
    onSelected: (FeedbackTopic) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.feedback_topic_label),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedbackTopic.entries.forEach { topic ->
                FilterChip(
                    selected = topic == selected,
                    onClick = { onSelected(topic) },
                    label = { Text(stringResource(topic.label)) },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/TopicSelector.kt
git commit -m "Add TopicSelector chip row for the feedback sheet"
```

---

## Task 10 — Build `ConditionalFields` composable

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/ConditionalFields.kt`

Renders the category-specific extras. `OTHER` renders nothing.

- [ ] **Step 1: Create `ConditionalFields.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_field_current_behaviour
import zed.rainxch.githubstore.core.presentation.res.feedback_field_desired_behaviour
import zed.rainxch.githubstore.core.presentation.res.feedback_field_expected_actual
import zed.rainxch.githubstore.core.presentation.res.feedback_field_proposed_solution
import zed.rainxch.githubstore.core.presentation.res.feedback_field_steps
import zed.rainxch.githubstore.core.presentation.res.feedback_field_use_case
import zed.rainxch.tweaks.presentation.feedback.FeedbackAction
import zed.rainxch.tweaks.presentation.feedback.FeedbackState
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackCategory

@Composable
fun ConditionalFields(
    state: FeedbackState,
    onAction: (FeedbackAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.category) {
            FeedbackCategory.BUG -> {
                MultilineField(
                    value = state.stepsToReproduce,
                    label = stringResource(Res.string.feedback_field_steps),
                    onValueChange = { onAction(FeedbackAction.OnStepsToReproduceChange(it)) },
                )
                MultilineField(
                    value = state.expectedActual,
                    label = stringResource(Res.string.feedback_field_expected_actual),
                    onValueChange = { onAction(FeedbackAction.OnExpectedActualChange(it)) },
                )
            }
            FeedbackCategory.FEATURE_REQUEST -> {
                MultilineField(
                    value = state.useCase,
                    label = stringResource(Res.string.feedback_field_use_case),
                    onValueChange = { onAction(FeedbackAction.OnUseCaseChange(it)) },
                )
                MultilineField(
                    value = state.proposedSolution,
                    label = stringResource(Res.string.feedback_field_proposed_solution),
                    onValueChange = { onAction(FeedbackAction.OnProposedSolutionChange(it)) },
                )
            }
            FeedbackCategory.CHANGE_REQUEST -> {
                MultilineField(
                    value = state.currentBehaviour,
                    label = stringResource(Res.string.feedback_field_current_behaviour),
                    onValueChange = { onAction(FeedbackAction.OnCurrentBehaviourChange(it)) },
                )
                MultilineField(
                    value = state.desiredBehaviour,
                    label = stringResource(Res.string.feedback_field_desired_behaviour),
                    onValueChange = { onAction(FeedbackAction.OnDesiredBehaviourChange(it)) },
                )
            }
            FeedbackCategory.OTHER -> { /* no extras */ }
        }
    }
}

@Composable
private fun MultilineField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/ConditionalFields.kt
git commit -m "Add ConditionalFields renderer for category-specific feedback extras"
```

---

## Task 11 — Build `DiagnosticsPreview` composable

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/DiagnosticsPreview.kt`

Read-only block + `Switch`. Shows the exact text that will be appended to the body, so the user sees what they're sending.

- [ ] **Step 1: Create `DiagnosticsPreview.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_diagnostics_header
import zed.rainxch.githubstore.core.presentation.res.feedback_diagnostics_include
import zed.rainxch.tweaks.presentation.feedback.model.DiagnosticsInfo
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel

@Composable
fun DiagnosticsPreview(
    diagnostics: DiagnosticsInfo?,
    channel: FeedbackChannel,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(Res.string.feedback_diagnostics_header),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.feedback_diagnostics_include),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggle() },
                )
            }

            if (enabled && diagnostics != null) {
                Text(
                    text = formatDiagnostics(diagnostics, channel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun formatDiagnostics(d: DiagnosticsInfo, channel: FeedbackChannel): String {
    val sb = StringBuilder()
    sb.append("App: GitHub Store v").append(d.appVersion).append('\n')
    sb.append("Platform: ").append(d.platform).append(' ').append(d.osVersion).append('\n')
    sb.append("Locale: ").append(d.locale)
    d.installerType?.let { sb.append('\n').append("Installer: ").append(it) }
    if (channel == FeedbackChannel.GITHUB) {
        d.githubUsername?.let { sb.append('\n').append("GitHub user: @").append(it) }
    }
    return sb.toString()
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/DiagnosticsPreview.kt
git commit -m "Add DiagnosticsPreview card with toggle for the feedback sheet"
```

---

## Task 12 — Build `SendActions` composable

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/SendActions.kt`

Bottom-pinned button row.

- [ ] **Step 1: Create `SendActions.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_send_via_email
import zed.rainxch.githubstore.core.presentation.res.feedback_send_via_github

@Composable
fun SendActions(
    canSend: Boolean,
    isSending: Boolean,
    onSendEmail: () -> Unit,
    onSendGithub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onSendGithub,
            enabled = canSend,
            modifier = Modifier.weight(1f),
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(stringResource(Res.string.feedback_send_via_github))
            }
        }
        Button(
            onClick = onSendEmail,
            enabled = canSend,
            modifier = Modifier.weight(1f),
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(Res.string.feedback_send_via_email))
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/SendActions.kt
git commit -m "Add SendActions row with email and GitHub send buttons"
```

---

## Task 13 — Build `FeedbackBottomSheet` shell

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/FeedbackBottomSheet.kt`

Full-screen `ModalBottomSheet` that hosts the `FeedbackViewModel`, lays out the form, and forwards send-success / send-error events upward.

- [ ] **Step 1: Create `FeedbackBottomSheet.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.feedback.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import zed.rainxch.core.presentation.utils.ObserveAsEvents
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.feedback_close
import zed.rainxch.githubstore.core.presentation.res.feedback_field_description
import zed.rainxch.githubstore.core.presentation.res.feedback_field_title
import zed.rainxch.githubstore.core.presentation.res.feedback_title
import zed.rainxch.tweaks.presentation.feedback.FeedbackAction
import zed.rainxch.tweaks.presentation.feedback.FeedbackEvent
import zed.rainxch.tweaks.presentation.feedback.FeedbackViewModel
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackBottomSheet(
    onDismiss: () -> Unit,
    onSent: (FeedbackChannel) -> Unit,
    onError: (String) -> Unit,
    viewModel: FeedbackViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is FeedbackEvent.OnSent -> onSent(event.channel)
            is FeedbackEvent.OnSendError -> onError(event.message)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.onAction(FeedbackAction.OnDismiss)
            onDismiss()
        },
        sheetState = sheetState,
        modifier = Modifier.fillMaxSize(),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.feedback_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = {
                    viewModel.onAction(FeedbackAction.OnDismiss)
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.feedback_close),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            CategorySelector(
                selected = state.category,
                onSelected = { viewModel.onAction(FeedbackAction.OnCategoryChange(it)) },
            )

            TopicSelector(
                selected = state.topic,
                onSelected = { viewModel.onAction(FeedbackAction.OnTopicChange(it)) },
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = { viewModel.onAction(FeedbackAction.OnTitleChange(it)) },
                label = { Text(stringResource(Res.string.feedback_field_title) + " *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = { viewModel.onAction(FeedbackAction.OnDescriptionChange(it)) },
                label = { Text(stringResource(Res.string.feedback_field_description) + " *") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            ConditionalFields(
                state = state,
                onAction = viewModel::onAction,
            )

            // Channel for the diagnostics preview is informational only —
            // the actual channel is decided when the user picks Send. We
            // pass GITHUB so the preview shows the username if present
            // (most permissive view); the composer still strips it for
            // the email send.
            DiagnosticsPreview(
                diagnostics = state.diagnostics,
                channel = FeedbackChannel.GITHUB,
                enabled = state.attachDiagnostics,
                onToggle = { viewModel.onAction(FeedbackAction.OnAttachDiagnosticsToggle) },
            )

            SendActions(
                canSend = state.canSend,
                isSending = state.isSending,
                onSendEmail = { viewModel.onAction(FeedbackAction.OnSendViaEmail) },
                onSendGithub = { viewModel.onAction(FeedbackAction.OnSendViaGithub) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
```

- [ ] **Step 2: Verify `ObserveAsEvents` import path**

Run: `grep -rn "fun ObserveAsEvents" core/presentation/src --include="*.kt"`
Expected: a file under `core/presentation/.../utils/` exporting `fun <T> ObserveAsEvents(flow: Flow<T>, ...)`. The import in the file above (`zed.rainxch.core.presentation.utils.ObserveAsEvents`) matches what `TweaksRoot.kt` already uses, so this should resolve. If grep shows a different package, adjust the import.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/FeedbackBottomSheet.kt
git commit -m "Add FeedbackBottomSheet shell composing the full feedback form"
```

---

## Task 14 — Wire `isFeedbackSheetVisible` into Tweaks state, action, and ViewModel

**Files:**
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksState.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksAction.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksViewModel.kt`

- [ ] **Step 1: Add `isFeedbackSheetVisible` to `TweaksState`**

Open `TweaksState.kt` and insert the new field immediately after `selectedAppLanguage` (around line 51), inside the data class:

```kotlin
    val selectedAppLanguage: String? = null,
    val isFeedbackSheetVisible: Boolean = false,
)
```

- [ ] **Step 2: Add the two new actions to `TweaksAction`**

Open `TweaksAction.kt` and add these two `data object`s anywhere inside the sealed interface (e.g. immediately after `OnHelpClick` at line 110):

```kotlin
    data object OnFeedbackClick : TweaksAction

    data object OnFeedbackDismiss : TweaksAction
```

- [ ] **Step 3: Handle them in `TweaksViewModel.onAction`**

Open `TweaksViewModel.kt` and locate the `onAction(action: TweaksAction)` `when` block (search for `TweaksAction.OnHelpClick`). Add two new branches:

```kotlin
            TweaksAction.OnFeedbackClick ->
                _state.update { it.copy(isFeedbackSheetVisible = true) }
            TweaksAction.OnFeedbackDismiss ->
                _state.update { it.copy(isFeedbackSheetVisible = false) }
```

If the `onAction` function uses a different style (e.g. dispatches to private methods), match that style — the simplest equivalent is two private one-liner methods called from `when`. The 34 KB `TweaksViewModel.kt` already contains many similar one-line state updates; pattern-match on the closest neighbour.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL. The `when (action)` exhaustiveness check ensures the two new branches don't break existing handling.

- [ ] **Step 5: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksState.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksAction.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksViewModel.kt
git commit -m "Add open/close flag and actions for the feedback bottom sheet"
```

---

## Task 15 — Mount `FeedbackBottomSheet` in `TweaksRoot`

**Files:**
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksRoot.kt`

- [ ] **Step 1: Add the imports**

Open `TweaksRoot.kt` and add these imports alongside the existing ones (alphabetical order — they go among the existing `zed.rainxch.tweaks.presentation.*` imports):

```kotlin
import zed.rainxch.tweaks.presentation.feedback.components.FeedbackBottomSheet
import zed.rainxch.tweaks.presentation.feedback.model.FeedbackChannel
import zed.rainxch.githubstore.core.presentation.res.feedback_send_error
import zed.rainxch.githubstore.core.presentation.res.feedback_send_success_email
import zed.rainxch.githubstore.core.presentation.res.feedback_send_success_github
```

- [ ] **Step 2: Mount the sheet next to the existing `ClearDownloadsDialog`**

Locate the `if (state.isClearDownloadsDialogVisible) { … }` block at the end of `TweaksRoot` (around line 158). Append a sibling block immediately after its closing brace:

```kotlin
    if (state.isFeedbackSheetVisible) {
        FeedbackBottomSheet(
            onDismiss = {
                viewModel.onAction(TweaksAction.OnFeedbackDismiss)
            },
            onSent = { channel ->
                viewModel.onAction(TweaksAction.OnFeedbackDismiss)
                coroutineScope.launch {
                    val msg =
                        when (channel) {
                            FeedbackChannel.EMAIL ->
                                getString(Res.string.feedback_send_success_email)
                            FeedbackChannel.GITHUB ->
                                getString(Res.string.feedback_send_success_github)
                        }
                    snackbarState.showSnackbar(msg)
                }
            },
            onError = { error ->
                coroutineScope.launch {
                    snackbarState.showSnackbar(
                        getString(Res.string.feedback_send_error, error),
                    )
                }
            },
        )
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksRoot.kt
git commit -m "Mount FeedbackBottomSheet from TweaksRoot with snackbar feedback"
```

---

## Task 16 — Replace `help_support` row in `About.kt` with a "Send feedback" row

**Files:**
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/About.kt`

The `help_support` string and `OnHelpClick` action are left in the codebase untouched (they may get a real wiring later). We're only replacing the row that uses them.

- [ ] **Step 1: Update imports**

Open `About.kt`. The `import androidx.compose.material.icons.filled.QuestionMark` line is no longer used after this task; remove it.

Add these imports (alphabetical with the rest):

```kotlin
import androidx.compose.material.icons.filled.Feedback
import zed.rainxch.githubstore.core.presentation.res.feedback_send
```

`Icons.Default.Feedback` is part of `material-icons-extended` which is already pulled in transitively (proven by the existing `Icons.Default.BugReport` use in `feature/details/.../sections/ReportIssue.kt`). If for any reason it's missing, fall back to `Icons.Default.BugReport` — the `material.icons.filled.BugReport` import.

Drop the `import zed.rainxch.githubstore.core.presentation.res.help_support` line — it's no longer referenced in this file. (The string itself stays in `strings.xml`; only this file's import goes.)

- [ ] **Step 2: Swap the second `AboutItem` block**

Locate the second `AboutItem` call (lines 73–95 in the current file — the one with `Icons.Filled.QuestionMark` and `stringResource(Res.string.help_support)`). Replace just that `AboutItem` invocation with:

```kotlin
            AboutItem(
                icon = Icons.Default.Feedback,
                title = stringResource(Res.string.feedback_send),
                actions = {
                    IconButton(
                        shape = IconButtonDefaults.shapes().shape,
                        onClick = {
                            onAction(TweaksAction.OnFeedbackClick)
                        },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
```

The `HorizontalDivider()` between the Version row and this row stays as-is.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build a debug APK and smoke-test the full flow**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL with no warnings related to the new code.

Then on Android (or Desktop with `./gradlew :composeApp:run`):
1. Open the app → bottom nav → Tweaks → scroll to the About card.
2. Tap "Send feedback" → bottom sheet opens fullscreen.
3. Toggle through categories — the conditional fields below Description should switch.
4. Toggle the diagnostics switch — the preview block disappears / reappears.
5. Type a title and description, tap "Send Email" → your default mail client opens with a prefilled draft to `hello@github-store.org`.
6. Reopen the sheet, tap "Open as GitHub Issue" → browser opens to a pre-filled `OpenHub-Store/GitHub-Store` new-issue page with labels.
7. After dismissing, reopen the sheet → form is reset to defaults.

If any of those steps fail, fix in place and re-test before committing.

- [ ] **Step 5: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/About.kt
git commit -m "Replace dead help_support row with Send feedback entry in Tweaks About"
```

---

## Plan complete

When all 16 tasks pass, the feature is shipped end-to-end: discoverable from Tweaks → About, full-screen bottom sheet with conditional category fields, opt-out diagnostics, dual-channel send (email + GitHub issue) with pre-filled labels, snackbar success / error feedback, and form reset on reopen.

The spec's `OnSent` event delivers the channel so the host can choose between two distinct success snackbars; `BrowserHelper`'s synchronous `onFailure` callback drives the error path. Form state lives in `FeedbackViewModel`, which is scoped through `koinViewModel()` and explicitly resets on `OnDismiss` so reopening the sheet always starts fresh without losing the once-collected diagnostics.
