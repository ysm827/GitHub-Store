# In-App Feedback Sheet (Tweaks)

**Date:** 2026-04-29
**Status:** Design approved, awaiting implementation plan
**Host module:** `feature/tweaks/presentation`

---

## 1. Problem

Today the only way for a user to report a bug or request a feature is to navigate to GitHub and open an issue manually. This is hostile to non-technical users (who don't have a GitHub account or don't want one), to privacy-sensitive users (who don't want their report indexed publicly forever), and to logged-out users (who get bounced through the GitHub login wall before they can even file).

The existing in-app `ReportIssue` row in the repo-details screen just opens `<repoUrl>/issues` in a browser — it's a routing shortcut, not a feedback flow. There is no equivalent for app-level feedback (the app itself, not a specific repo).

We want a guided in-app form that collects a structured report and hands it off to either email or a pre-filled GitHub issue, the user's choice.

## 2. Goals

- Surface a "Send feedback" entry from Tweaks (replacing the dead `help_support` row in About).
- Open a full-screen `ModalBottomSheet` containing a structured form (category, topic, title, description, category-specific extras, optional auto-attached diagnostics).
- Let the user pick the delivery channel per report: open a pre-filled `mailto:` to `hello@github-store.org`, or a pre-filled GitHub issue URL on `OpenHub-Store/GitHub-Store`.
- Keep diagnostics opt-out-able. Never include the user's GitHub username on the email path.
- Ship as KMP-common code that works identically on Android and Desktop.

## 3. Non-goals

- File / image attachments (`mailto:` and GitHub URL pre-fill don't support them).
- Auto-collected log files (no log pipeline today; would also bust URL length caps).
- A server-side feedback API or queue.
- Localizing the rendered body content — body headings stay English so triage stays uniform; user free-text remains in whatever language they typed.
- Wiring up the existing `help_support` action to anything else — it's being replaced.

## 4. Architecture

### 4.1 File structure

New sub-package under tweaks:

```
feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/
├── FeedbackViewModel.kt
├── FeedbackState.kt
├── FeedbackAction.kt
├── FeedbackEvent.kt
├── model/
│   ├── FeedbackCategory.kt              # enum + GitHub label mapping
│   ├── FeedbackTopic.kt                 # enum + GitHub label mapping
│   ├── FeedbackChannel.kt               # enum: EMAIL, GITHUB
│   └── DiagnosticsInfo.kt               # data class
├── util/
│   └── FeedbackComposer.kt              # pure URL/body builder
└── components/
    ├── FeedbackBottomSheet.kt           # ModalBottomSheet shell + scrollable host
    ├── CategorySelector.kt              # radio group
    ├── TopicSelector.kt                 # FilterChip flow row
    ├── ConditionalFields.kt             # category-specific extras
    ├── DiagnosticsPreview.kt            # read-only block + Switch
    └── SendActions.kt                   # bottom button row
```

Edits in existing files (small):

```
TweaksState.kt                                            # + isFeedbackSheetVisible flag
TweaksAction.kt                                           # + OnFeedbackClick, OnFeedbackDismiss
TweaksViewModel.kt                                        # handle the two new actions
TweaksRoot.kt                                             # mount FeedbackBottomSheet conditionally
components/sections/About.kt                              # replace help_support row with feedback row
composeApp/.../app/di/ViewModelsModule.kt                 # register FeedbackViewModel (central app-level Koin module)
```

### 4.2 State ownership split

Two ViewModels:

- `TweaksViewModel` owns one piece of feedback state — `isFeedbackSheetVisible: Boolean`. It handles `OnFeedbackClick` (set `true`) and `OnFeedbackDismiss` (set `false`).
- `FeedbackViewModel` owns everything inside the sheet (category, topic, all text fields, diagnostics, sending state). It is acquired with `koinViewModel()` inside the `FeedbackBottomSheet` composable. Note: `koinViewModel()` resolves against the surrounding `ViewModelStoreOwner` (the Tweaks nav entry), so the ViewModel may outlive the sheet's composition. To guarantee a fresh form on every reopen, `FeedbackViewModel` handles `OnDismiss` by resetting `_state.value` to a default `FeedbackState` (preserving the already-collected `diagnostics` so we don't re-query repositories on reopen).

This keeps `TweaksViewModel` (already 34 KB) from absorbing another ~10 fields and ~12 actions, and makes the form independently testable and portable.

### 4.3 Types

```kotlin
enum class FeedbackCategory(val label: StringResource, val githubLabel: String) {
    BUG(Res.string.feedback_category_bug, "type:bug"),
    FEATURE_REQUEST(Res.string.feedback_category_feature, "type:feature"),
    CHANGE_REQUEST(Res.string.feedback_category_change, "type:change"),
    OTHER(Res.string.feedback_category_other, "type:other"),
}

enum class FeedbackTopic(val label: StringResource, val githubLabel: String) {
    INSTALL_UPDATE(Res.string.feedback_topic_install_update, "area:install"),
    SEARCH_DISCOVERY(Res.string.feedback_topic_search, "area:search"),
    REPO_DETAILS(Res.string.feedback_topic_details, "area:details"),
    AUTH_ACCOUNT(Res.string.feedback_topic_auth, "area:auth"),
    UI_UX(Res.string.feedback_topic_ui, "area:ui"),
    TRANSLATION(Res.string.feedback_topic_translation, "area:translation"),
    PERFORMANCE(Res.string.feedback_topic_performance, "area:performance"),
    OTHER(Res.string.feedback_topic_other, "area:other"),
}

enum class FeedbackChannel { EMAIL, GITHUB }

data class DiagnosticsInfo(
    val appVersion: String,        // e.g. "1.6.2 (13)"
    val platform: String,          // e.g. "Android 14 (API 34)" / "Desktop · macOS 14.4"
    val locale: String,            // e.g. "en-US"
    val installerType: String?,    // e.g. "Shizuku (READY)" — Android only, null on Desktop
    val githubUsername: String?,   // null when logged out OR sending via email
)

data class FeedbackState(
    val category: FeedbackCategory = FeedbackCategory.BUG,
    val topic: FeedbackTopic = FeedbackTopic.OTHER,
    val title: String = "",
    val description: String = "",

    // Bug-only
    val stepsToReproduce: String = "",
    val expectedActual: String = "",

    // Feature request-only
    val useCase: String = "",
    val proposedSolution: String = "",

    // Change request-only
    val currentBehaviour: String = "",
    val desiredBehaviour: String = "",

    val attachDiagnostics: Boolean = true,
    val diagnostics: DiagnosticsInfo? = null,
    val isSending: Boolean = false,
) {
    val canSend: Boolean
        get() = title.isNotBlank() && description.isNotBlank() && !isSending
}

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

sealed interface FeedbackEvent {
    /** Emitted after BrowserHelper.openUrl returned without invoking onFailure.
     *  The host (TweaksRoot) collapses the sheet and shows a per-channel success snackbar. */
    data class OnSent(val channel: FeedbackChannel) : FeedbackEvent
    data class OnSendError(val message: String) : FeedbackEvent
}
```

### 4.4 Body composition

`FeedbackComposer` is a pure object/file (no Compose, no Koin) so it is trivially testable in `commonTest`.

Markdown body, headings always English:

```
## Description
<description>

## Steps to reproduce              (Bug only, omitted if blank)
<stepsToReproduce>

## Expected vs actual              (Bug only, omitted if blank)
<expectedActual>

## Use case                        (Feature only, omitted if blank)
<useCase>

## Proposed solution               (Feature only, omitted if blank)
<proposedSolution>

## Current behaviour               (Change only, omitted if blank)
<currentBehaviour>

## Desired behaviour               (Change only, omitted if blank)
<desiredBehaviour>

---
**Diagnostics**                    (only if attachDiagnostics = true)
- App: GitHub Store v1.6.2 (13)
- Platform: Android 14 (API 34)
- Locale: en-US
- Installer: Shizuku (READY)       (Android only, when non-null)
- GitHub user: @username           (GITHUB channel only — never on EMAIL)
```

URLs:

- **Email**: `mailto:hello@github-store.org?subject=<urlencoded(title)>&body=<urlencoded(body)>`
- **GitHub**: `https://github.com/OpenHub-Store/GitHub-Store/issues/new?title=<urlencoded(title)>&body=<urlencoded(body)>&labels=<urlencoded("type:bug,area:install")>`

Length cap: GitHub silently truncates above ~8 KB. The composer caps the **raw body** (before URL encoding) at 7,500 characters and appends `\n\n…[truncated]` if it overflows. After URL encoding the typical 3× expansion still leaves headroom under the 8 KB ceiling. The same cap applies to email for symmetry. Title is not capped (in practice always short).

The composer's signature takes a `FeedbackChannel` parameter. The email branch passes `username = null` to the diagnostics renderer regardless of state, so "never leak the username via email" is a property of the composer, not a thing implementers have to remember at the call site.

### 4.5 Diagnostics gathering

`FeedbackViewModel` injects the following via Koin:

- `ProfileRepository` (from `feature/profile/domain`) — `getVersionName(): String` and `getUser(): Flow<UserProfile?>` (for GitHub username; collect first emission once on `init`).
- `TweaksRepository` (from `core/domain`) — `getInstallerType(): Flow<InstallerType>` (Android-meaningful only; on Desktop it's still a valid value, just rendered as `null` in `DiagnosticsInfo.installerType` because it's not relevant) and `getAppLanguage(): Flow<String?>` (user-overridden BCP-47 tag, `null` = follow system).
- `getPlatform()` (already exists in `core/domain`) — returns the `Platform` enum for the platform name (`"Android"`, `"Windows"`, `"macOS"`, `"Linux"`).

Two new top-level `expect`/`actual` functions added to `core/domain` to fill the gaps the existing `Platform` enum doesn't cover:

- `expect fun getOsVersion(): String` — Android: `"14 (API 34)"`; JVM: `System.getProperty("os.version")`.
- `expect fun getSystemLocaleTag(): String` — Android: `Locale.getDefault().toLanguageTag()`; JVM: `Locale.getDefault().toLanguageTag()`. Falls back to `"und"` (BCP-47 undefined) if the JVM returns blank.

`DiagnosticsInfo.locale` is computed as `tweaksRepository.getAppLanguage().first() ?: getSystemLocaleTag()` so the user's app-language override wins, falling back to the actual system locale.

Diagnostics are computed once in `init` and stored on state. They don't change while the sheet is open, so we don't re-collect on every action.

### 4.6 Send flow

```
User taps Send via Email           User taps Send via GitHub
        │                                  │
        ▼                                  ▼
  isSending = true                   isSending = true
  body = compose(state, EMAIL)       body = compose(state, GITHUB)
  url  = mailto:...                  url  = https://github.com/.../issues/new?...
        │                                  │
        └──────────────┬───────────────────┘
                       ▼
        BrowserHelper.openUrl(url, onFailure = { msg ->
            isSending = false
            emit FeedbackEvent.OnSendError(msg)
        })
                       │
        on no exception (treat as success):
                       ▼
        emit FeedbackEvent.OnSent
        TweaksRoot collects → onAction(TweaksAction.OnFeedbackDismiss)
        TweaksRoot also shows snackbar: "Thanks — opening your { mail client | browser }"
```

`BrowserHelper.openUrl` signature is `fun openUrl(url: String, onFailure: (error: String) -> Unit = { })`. We treat "`onFailure` was not invoked synchronously" as success — there's no positive callback for "user actually saw the email composer / GitHub page". `isSending` is held briefly (~250 ms) so the buttons visibly disable and double-tap is impossible, then reset on success.

On `OnSendError`, the sheet stays open so the user can retry or switch channel.

### 4.7 UI layout

`FeedbackBottomSheet` composable:

```kotlin
ModalBottomSheet(
    onDismissRequest = { onAction(FeedbackAction.OnDismiss) },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.fillMaxSize(),
)
```

Inner `Column` (vertically scrollable):

1. Drag handle (default `ModalBottomSheet` handle) + header row: title `feedback_title`, trailing close `IconButton`.
2. `CategorySelector` — `selectableGroup` of 4 `Row(Modifier.selectable(role = Role.RadioButton))`.
3. `TopicSelector` — `FlowRow` of 8 `FilterChip`s, single-select.
4. `OutlinedTextField` for title (required, single-line, label asterisked).
5. `OutlinedTextField` for description (required, multi-line, `minLines = 4`).
6. `ConditionalFields` — `when (state.category)` dispatches to:
   - `BUG` → two text fields (steps, expected/actual)
   - `FEATURE_REQUEST` → two text fields (use case, proposed solution)
   - `CHANGE_REQUEST` → two text fields (current behaviour, desired behaviour)
   - `OTHER` → emits nothing
7. `DiagnosticsPreview` — `OutlinedCard` (matching existing `About.kt` styling: `RoundedCornerShape(32.dp)`, `surfaceContainerLowest`) containing a `Switch` (bound to `attachDiagnostics`) and the formatted preview text. Preview text is the exact diagnostics block that will appear in the body, so the user sees what they're sending.
8. `SendActions` row at the bottom of the scrollable area: `OutlinedButton("Open as GitHub Issue")` + filled `Button("Send Email")`. Both:
   - disabled when `!state.canSend`
   - show a small `CircularProgressIndicator` (replacing their leading icon) when `state.isSending`

Liquid glass: respect `state.isLiquidGlassEnabled` from `TweaksState` — the parent `Scaffold` already gates this for the surrounding screen, and `ModalBottomSheet` inherits the theme automatically. No extra plumbing needed inside the sheet itself.

Visual styling matches existing tweaks/profile sections: `RoundedCornerShape(32.dp)` for cards, `surfaceContainerLowest` / `surfaceContainerHigh` for backgrounds, `MaterialTheme.typography.titleMedium` for section labels.

### 4.8 Strings (i18n)

New keys added to `core/presentation/.../values/strings.xml` (English) and mirrored to all 12 other locale files. Translators backfill on the next localization pass — initial values are English placeholders in non-English files (this matches how new strings have historically been added to the project).

Approximately 28 keys:

- `feedback_title`, `feedback_close`
- `feedback_category_label`, `feedback_category_bug`, `feedback_category_feature`, `feedback_category_change`, `feedback_category_other`
- `feedback_topic_label`, `feedback_topic_install_update`, `feedback_topic_search`, `feedback_topic_details`, `feedback_topic_auth`, `feedback_topic_ui`, `feedback_topic_translation`, `feedback_topic_performance`, `feedback_topic_other`
- `feedback_field_title`, `feedback_field_description`
- `feedback_field_steps`, `feedback_field_expected_actual`
- `feedback_field_use_case`, `feedback_field_proposed_solution`
- `feedback_field_current_behaviour`, `feedback_field_desired_behaviour`
- `feedback_diagnostics_header`, `feedback_diagnostics_include`
- `feedback_send_via_email`, `feedback_send_via_github`
- `feedback_send_success_email`, `feedback_send_success_github`, `feedback_send_error`
- `feedback_send` (the new About-row label, replacing `help_support`)

The `help_support` key is left in the resources (not deleted) — it's harmless and may get a real wiring later. Only the *row* in `About.kt` is replaced.

## 5. Wiring summary

- `About.kt` row 2: replace `help_support` text + `OnHelpClick` action with `feedback_send` text + `OnFeedbackClick` action.
- `TweaksAction`: add `OnFeedbackClick` and `OnFeedbackDismiss` (both `data object`).
- `TweaksState`: add `isFeedbackSheetVisible: Boolean = false`.
- `TweaksViewModel.onAction`: handle the two new actions by flipping the flag.
- `TweaksRoot`: when `state.isFeedbackSheetVisible` is true, render `FeedbackBottomSheet`. Forward its `OnSendError` event to the existing `snackbarState`. Forward its `OnSent` event to `viewModel.onAction(TweaksAction.OnFeedbackDismiss)` plus the success snackbar.
- `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`: register `FeedbackViewModel` via `viewModelOf(::FeedbackViewModel)`. (Tweaks itself has no `data/` Koin module — all ViewModels are registered centrally in this app-level module, alongside `TweaksViewModel`.)
- New constants live next to the composer:
  - `FEEDBACK_EMAIL = "hello@github-store.org"`
  - `FEEDBACK_REPO = "OpenHub-Store/GitHub-Store"`
  - `FEEDBACK_BODY_MAX_CHARS = 7_500`
