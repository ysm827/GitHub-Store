# E5 Mirror System

**Date:** 2026-04-29
**Status:** Design approved, awaiting implementation plan
**Source of truth for backend contract:** [`roadmap/E5_CLIENT_HANDOFF.md`](../../../roadmap/E5_CLIENT_HANDOFF.md)
**Supersedes:** parts of `roadmap/FREE_FEATURES.md §E5` that reference E25 / dead mirrors

---

## 1. Problem

Users on networks where `github.com` is slow or blocked can't reliably download release assets. The basic per-scope HTTP/SOCKS proxy already exists, but proxies don't help with GitHub's geo-distributed CDN — what works is a community-run reverse proxy ("gh-proxy"-style mirror) that forwards through the user's request to GitHub from a server with better connectivity. Users in China especially need this; the user survey has 3 explicit asks for it.

The basic proxy + a single hardcoded mirror URL field would be a v0 fix. The spec demands more: a curated mirror catalog with live status, automatic fallback, and binary-integrity verification — because trusting a third-party intermediary with downloaded executables requires SHA-256 verification at the client.

## 2. Goals

- Curated mirror picker (Tweaks → Download Mirror) with status + latency for each entry, fed from `GET /v1/mirrors/list` (24h-cached, bundled fallback).
- Automatic URL rewriting for all GitHub-bound traffic (`github.com`, `api.github.com`, `raw.githubusercontent.com`, `objects.githubusercontent.com`) when a non-`direct` mirror is selected. Backend (`api.github-store.org`) calls must NEVER be rewritten.
- Multi-source race for release-asset downloads: direct GitHub + selected mirror in parallel; first valid stream wins.
- Mandatory SHA-256 verification of every downloaded asset; mismatch deletes the file and surfaces an error.
- Auto-suggest "try a faster mirror" prompt after 3 sustained-slow downloads in 10 minutes (only when `direct` is currently selected).
- Custom mirror entry: user-supplied template URL containing `{url}`.
- Test-connection button in the picker.

## 3. Non-goals

- GitHub Store CDN ("E25") — explicitly killed in the handoff. No first-party CDN entry, no consent dialog, no `type: "first-party"`.
- Backend-side test endpoint (`/v1/mirrors/{id}/test`) — test connection is client-side only.
- A user-facing toggle to disable SHA-256 verification — handoff says "ship with verification always-on first, add the toggle later if asked."
- Racing API calls (rate-limit risk).
- Persisting the test-connection result.
- Pre-selecting a mirror in the auto-suggest sheet (UX rule: picking is always the user's action).

## 4. Architecture

### 4.1 File structure

**New files:**

```
core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/
├── model/
│   ├── MirrorConfig.kt
│   ├── MirrorPreference.kt
│   ├── MirrorStatus.kt
│   └── MirrorType.kt
├── repository/
│   └── MirrorRepository.kt
├── network/
│   ├── MirrorRewriter.kt
│   ├── DigestVerifier.kt
│   └── SlowDownloadDetector.kt
└── system/
    └── MultiSourceDownloader.kt

core/data/src/commonMain/kotlin/zed/rainxch/core/data/
├── mirror/
│   ├── MirrorRepositoryImpl.kt
│   ├── MirrorPersistence.kt
│   └── BundledMirrors.kt
├── network/
│   ├── MirrorRewriteInterceptor.kt
│   └── MirrorApiClient.kt
└── dto/
    └── MirrorListResponse.kt

core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/
└── DigestVerifierImpl.kt          # commonMain — uses kotlinx-io / Okio if available, else expect/actual

core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/
├── MultiSourceDownloaderImpl.kt
└── SlowDownloadDetectorImpl.kt

feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/
├── MirrorPickerRoot.kt
├── MirrorPickerViewModel.kt
├── MirrorPickerState.kt
├── MirrorPickerAction.kt
├── MirrorPickerEvent.kt
└── components/
    ├── MirrorRow.kt
    ├── CustomMirrorDialog.kt
    ├── DeployYourOwnHint.kt
    └── AutoSuggestMirrorSheet.kt    # mounted from app root, not the picker
```

**Edited files:**

```
core/data/.../network/HttpClientFactory.kt           # install MirrorRewriteInterceptor on the GitHub client only
core/data/.../network/ProxyManager.kt                # + currentMirrorTemplate(): String?
core/data/.../dto/AssetNetwork.kt                    # + digest field
core/domain/.../model/GithubAsset.kt                 # + digest field
core/data/.../system/DownloadOrchestratorImpl.kt     # use MultiSourceDownloader + DigestVerifier
core/presentation/.../values/strings.xml             # + ~22 keys
composeApp/.../app/navigation/GithubStoreGraph.kt    # + MirrorPickerScreen route
composeApp/.../app/AppNavigation.kt                  # wire route + auto-suggest sheet
composeApp/.../app/di/ViewModelsModule.kt            # register MirrorPickerViewModel
feature/tweaks/.../components/sections/Network.kt    # add "Download Mirror" entry tile
feature/tweaks/.../TweaksAction.kt                   # + OnMirrorPickerClick
feature/tweaks/.../TweaksRoot.kt                     # forward OnMirrorPickerClick to navigation
```

### 4.2 Domain types

```kotlin
enum class MirrorStatus { OK, DEGRADED, DOWN, UNKNOWN }
enum class MirrorType { OFFICIAL, COMMUNITY }

data class MirrorConfig(
    val id: String,
    val name: String,
    val urlTemplate: String?,        // null for "direct"
    val type: MirrorType,
    val status: MirrorStatus,
    val latencyMs: Int?,
    val lastCheckedAt: Instant?,
)

sealed interface MirrorPreference {
    data object Direct : MirrorPreference
    data class Selected(val id: String) : MirrorPreference
    data class Custom(val template: String) : MirrorPreference
}
```

```kotlin
interface MirrorRepository {
    fun observeCatalog(): Flow<List<MirrorConfig>>
    suspend fun refreshCatalog(): Result<Unit>
    fun observePreference(): Flow<MirrorPreference>
    suspend fun setPreference(pref: MirrorPreference)

    /** Effective template string for the current preference, or null when Direct. */
    fun currentTemplate(): String?
}

object MirrorRewriter {
    private val rewriteHosts = setOf(
        "github.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun shouldRewrite(url: String): Boolean = /* parse host and check membership */
    fun applyTemplate(template: String, githubUrl: String): String =
        template.replace("{url}", githubUrl)
}

interface DigestVerifier {
    /** Returns null on success; non-null with the failure reason on mismatch / IO error. */
    suspend fun verify(filePath: String, expectedDigest: String): String?
}

interface SlowDownloadDetector {
    val suggestMirror: Flow<Unit>
    fun observe(progress: Flow<DownloadProgress>)
}

interface MultiSourceDownloader {
    /** Races direct + mirror; cancels the loser; returns the winner's downloaded path. */
    fun download(githubUrl: String, suggestedFileName: String?): Flow<DownloadProgress>
}
```

### 4.3 Catalog fetch + persistence

`MirrorRepositoryImpl`:

- On first `observeCatalog()` subscription: emit DataStore-cached list (or bundled fallback if no cache exists), then fire a background `refreshCatalog()`.
- Cache keys (DataStore):
  - `CACHED_MIRROR_LIST_JSON` (the raw `MirrorListResponse` JSON)
  - `CACHED_MIRROR_LIST_AT` (epoch millis)
  - `PREFERRED_MIRROR_KEY` (one of `direct` | a backend `id` | the literal `custom`)
  - `CUSTOM_MIRROR_TEMPLATE` (only set when preference is `Custom`)
  - `AUTO_SUGGEST_SNOOZE_UNTIL` (epoch millis; nullable)
  - `AUTO_SUGGEST_DISMISSED` (Boolean)
- TTL = 24h. On 24h-stale + offline, return the last cached list (or bundled if cache empty).
- Catalog responses are fetched through `MirrorApiClient`, a separate `HttpClient` that does NOT have `MirrorRewriteInterceptor` installed — `api.github-store.org` calls must never be rewritten.
- After every successful refresh, if the user's currently-selected mirror id is missing from the fresh list, the repo flips preference to `Direct` and emits a `MirrorRemoved(displayName)` signal (via a separate `Channel` exposed through `observeRemovedNotices(): Flow<MirrorRemoved>`). The UI subscribes from a long-lived host (app root or the picker) and toasts.
- `Custom` preference is unaffected by refresh — user-supplied template can never "disappear."

`BundledMirrors` is the static list of 6 entries from the handoff (direct, ghfast_top, moeyy_xyz, gh_proxy_com, ghps_cc, gh_99988866_xyz). Bundled entries report `status = UNKNOWN`, `latencyMs = null`, `lastCheckedAt = null`. They're only emitted before the first successful fetch (or when the cache has been wiped).

### 4.4 URL rewriting

`MirrorRewriteInterceptor` is a Ktor client plugin installed on the **GitHub-bound** `HttpClient` only (the one created by `createGitHubHttpClient` in `HttpClientFactory.kt`). Backend-bound clients (`BackendApiClient`, `MirrorApiClient`) do NOT install it.

```kotlin
install("MirrorRewrite") {
    intercept(HttpSendPipeline.Before) {
        val original = context.url.buildString()
        if (MirrorRewriter.shouldRewrite(original)) {
            val template = ProxyManager.currentMirrorTemplate()
            if (template != null) {
                val rewritten = MirrorRewriter.applyTemplate(template, original)
                context.url.takeFrom(Url(rewritten))
            }
        }
    }
}
```

`ProxyManager.currentMirrorTemplate()` is a synchronous accessor that returns the latest template from a thread-safe in-memory `AtomicReference<String?>`. The reference is kept in sync by a long-lived collector (`CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned by `ProxyManager`) that observes `mirrorRepository.observePreference()` and writes the resolved template on every emission.

For the multi-source race the direct branch needs to bypass the interceptor. The request marks itself with a Ktor `AttributeKey<Boolean>` (`NO_MIRROR_REWRITE`); the interceptor checks this attribute *before* anything else and skips rewriting when it's `true`. The `MirrorRewriteInterceptor` is the only consumer of this attribute.

`HttpRedirect { checkHttpMethod = false }` is added (or already present — verify) on the GitHub `HttpClient` so a mirror that surfaces an `objects.githubusercontent.com` redirect to the client also gets rewritten on the next hop.

### 4.5 Multi-source race

`DownloadOrchestratorImpl` orchestrates downloads today. With a non-Direct preference:

```
suspend fun raceDownload(githubUrl: String, fileName: String): Flow<DownloadProgress> {
    val template = mirrorRepo.currentTemplate()
    if (template == null) return downloader.download(githubUrl, fileName)

    val mirrorUrl = MirrorRewriter.applyTemplate(template, githubUrl)

    return channelFlow {
        coroutineScope {
            val direct = async { downloader.download(githubUrl, "$fileName.direct.tmp", noRewrite = true).toList() }
            val mirror = async { downloader.download(mirrorUrl, "$fileName.mirror.tmp").toList() }

            select<Unit> {
                direct.onAwait { /* keep direct, cancel mirror, rename to fileName, emit consolidated progress */ }
                mirror.onAwait { /* keep mirror, cancel direct, rename to fileName */ }
            }
        }
    }
}
```

Constraints:
- Race applies ONLY to release-asset downloads (`browser_download_url` from `AssetNetwork`). API calls do not race — they go through the mirror only.
- "Won" = first source to deliver HTTP 2xx + start streaming bytes. We do NOT wait for both to complete to compare.
- The losing coroutine is cancelled cleanly via the surrounding `coroutineScope`'s child cancellation.
- The losing temp file is deleted in a `finally` block.
- If both sources fail, the resulting error message includes both reasons.

### 4.6 SHA-256 verification

`AssetNetwork.digest` is the new field carrying GitHub's `sha256:abc123...` value. Default `null` because:
- Older releases predate GitHub's `digest` field.
- Some asset types (e.g. tarballs auto-generated by the Releases API) don't have one.
- A future GitHub API change could omit the field for new release shapes.

Skipping verification when the digest is null is a deliberate choice — failing every download without a digest would block legacy releases entirely. The Kermit log line is the operator's signal to investigate frequency post-ship; if it fires often, we revisit.

`DigestVerifier.verify(path, expected)`:
- Strips the `sha256:` prefix from `expected`.
- Streams the file through `MessageDigest.getInstance("SHA-256")`.
- Returns `null` on match, a non-null reason on mismatch or IO error.

`DownloadOrchestratorImpl` after a successful download:
- If `asset.digest == null`: skip verification, log `Kermit.i { "No digest for ${asset.name}, skipping verification" }`. Do NOT fail.
- Else: call `DigestVerifier.verify(filePath, asset.digest)`.
- On mismatch: delete file, transition entry to `DownloadStage.Failed` with `errorMessage = "Checksum mismatch — file may have been tampered with"`.

The digest field reaches `DownloadOrchestratorImpl` through `GithubAsset.digest` (which is populated from `AssetNetwork.digest` in the existing mapper). The `GithubAsset` model gains a `val digest: String? = null` field; the mapper propagates it.

### 4.7 Slow-download detector + auto-suggest

`SlowDownloadDetectorImpl`:
- Maintains a 30s rolling window of bytes-per-second (sliding) per active download.
- When the average over the window stays below 100 KB/s for the full window: record one slow event with `Clock.System.now()`.
- Old events outside the 10-min window are pruned on each tick.
- When `recentSlowEvents.size >= 3`:
  - AND `mirrorRepo.currentTemplate() == null` (user is on Direct)
  - AND `AUTO_SUGGEST_DISMISSED == false`
  - AND `AUTO_SUGGEST_SNOOZE_UNTIL == null OR snoozeUntil < now`
  - → emit `Unit` on `suggestMirror` flow and clear `recentSlowEvents` (so the same burst doesn't re-trigger).

`AutoSuggestMirrorSheet` is a `ModalBottomSheet` mounted at the app root (`AppNavigation.kt` or a wrapping container) so it can appear over any screen where downloads are happening. It collects from a `MainViewModel` (or new `AutoSuggestViewModel`) that owns the detector subscription. Three buttons:

- **Pick one** → `onAction(AutoSuggestAction.OnPickOne)` → navigate to `MirrorPickerScreen`, dismiss sheet
- **Maybe later** → set `AUTO_SUGGEST_SNOOZE_UNTIL = now + 24h`, dismiss sheet
- **Don't ask again** → set `AUTO_SUGGEST_DISMISSED = true`, dismiss sheet

The sheet does NOT pre-select any mirror. Its only action is opening the picker.

### 4.8 Test connection

In `MirrorPickerViewModel`:

```kotlin
private suspend fun runTest() {
    _state.update { it.copy(isTesting = true, testResult = null) }
    val pref = state.value.preference
    val client = httpClientFactory.testClient(pref)   // a transient client honoring `pref`
    val result = withTimeoutOrNull(5_000) {
        runCatching {
            val start = TimeSource.Monotonic.markNow()
            val response = client.get("https://api.github.com/zen")
            val elapsedMs = start.elapsedNow().inWholeMilliseconds
            response.status to elapsedMs
        }
    }
    val testResult = when {
        result == null -> TestResult.Timeout
        result.isSuccess -> {
            val (status, ms) = result.getOrThrow()
            if (status.isSuccess()) TestResult.Success(ms) else TestResult.HttpError(status.value)
        }
        result.exceptionOrNull() is UnresolvedAddressException -> TestResult.DnsFailure
        else -> TestResult.Other(result.exceptionOrNull()?.message ?: "Unknown error")
    }
    _state.update { it.copy(isTesting = false, testResult = testResult) }
    client.close()
}
```

`httpClientFactory.testClient(pref)` is a new helper — builds a one-shot client with the same MirrorRewriteInterceptor set up but configured to use `pref` instead of the global `ProxyManager.currentMirrorTemplate()`. Closed after every test.

### 4.9 UI — picker screen

`MirrorPickerRoot` is the host composable. State pattern matches existing screens (`SponsorScreen`, `TweaksRoot`):

- `Scaffold` with `TopAppBar("Download Mirror")` + back button
- `LazyColumn` body:
  - Header description text
  - Section header "Official"
  - `MirrorRow` for `direct` (radio + status dot + latency)
  - Section header "Community"
  - `MirrorRow` for each community mirror (5 entries)
  - `MirrorRow` for "Custom mirror..." (radio; on click, opens dialog)
  - `Divider`
  - "Test selected" `Button` → triggers `OnTest`; shows `CircularProgressIndicator` while `isTesting`
  - `TestResultText` rendering the latest result with appropriate icon
  - `DeployYourOwnHint` linking to `https://github.com/hunshcn/gh-proxy`

`MirrorRow` styling:
- 8.dp filled circle, color `MaterialTheme.colorScheme.primary` (OK) / `tertiary` (DEGRADED) / `error` (DOWN) / `outline` (UNKNOWN)
- Latency badge: small text `240ms`, `(down)`, `?`, or empty
- `Modifier.selectable(role = Role.RadioButton)` for accessibility (matches `CategorySelector` from the feedback feature)

`CustomMirrorDialog`:
- `AlertDialog` with `OutlinedTextField` for the template
- Validation runs on every change:
  - Empty → no error shown yet
  - Doesn't start with `https://` → error
  - Doesn't contain literal `{url}` exactly once → error
  - Else → no error
- Save button is disabled while there's an error or empty input
- Save dispatches `OnCustomMirrorConfirm(template)`; ViewModel persists via `mirrorRepo.setPreference(MirrorPreference.Custom(template))`
- Cancel dispatches `OnCustomMirrorDismiss`

### 4.10 Tweaks entry

`Network.kt` (existing proxy section) gains a tile at the top of the surrounding card group:

```
Download Mirror                  >
<current selection name>
```

Tap → dispatches `TweaksAction.OnMirrorPickerClick` → handled in `TweaksRoot.kt` → navigates to `MirrorPickerScreen`.

The tile reads the current selection name through a new field on `TweaksState` (`currentMirrorName: String`) populated from the same `mirrorRepo.observePreference()` + `observeCatalog()` join in `TweaksViewModel`.

## 5. Strings (i18n)

~22 new keys in `core/presentation/.../values/strings.xml` (English only; non-English locales fall back). Approximate keys:

- `mirror_picker_title`, `mirror_picker_description`
- `mirror_section_official`, `mirror_section_community`
- `mirror_status_ok`, `mirror_status_degraded`, `mirror_status_down`, `mirror_status_unknown`
- `mirror_custom_label`, `mirror_custom_dialog_title`, `mirror_custom_dialog_hint`, `mirror_custom_validation_https`, `mirror_custom_validation_template`
- `mirror_test_button`, `mirror_test_in_progress`, `mirror_test_success`, `mirror_test_http_error`, `mirror_test_timeout`, `mirror_test_dns_fail`, `mirror_test_other`
- `mirror_deploy_your_own_hint`
- `mirror_removed_toast` (`%1$s is no longer available, switched to Direct GitHub.`)
- `mirror_tweaks_entry_label` (`Download Mirror`)
- `mirror_auto_suggest_title`, `mirror_auto_suggest_body`
- `mirror_auto_suggest_pick_one`, `mirror_auto_suggest_maybe_later`, `mirror_auto_suggest_dont_ask_again`
- `mirror_digest_mismatch_error`

## 6. Security and trust model

- The mirror catalog is served by **our backend**, not the mirrors. A compromised mirror cannot inject itself into the picker.
- The `digest` field is fetched from `api.github.com` — that fetch DOES go through the mirror when one is selected, so a malicious mirror that consistently rewrites both the digest response and the binary would not be caught by SHA-256 alone. The handoff acknowledges this; the verification still catches accidental corruption (broken mirror, MITM, partial download) and most tampering attempts (where the attacker doesn't control both responses consistently).
- The threat model documented in `roadmap/E5_CLIENT_HANDOFF.md` §5 takes precedence over any inferred policy here.

## 7. Open issues / deferred work

- **Verification toggle**: the spec defers this until requested.
- **Federated digest cache**: a future enhancement could fetch digests from a different trust path (our backend) for stronger guarantees. Out of scope for v1.
- **Per-asset mirror choice**: not in scope — a single global mirror preference applies to all downloads.
