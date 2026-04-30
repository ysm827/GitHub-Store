# E5 Mirror System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the curated GitHub mirror system end-to-end — picker UI in Tweaks → Download Mirror, automatic URL rewriting for non-Direct preferences, multi-source race + SHA-256 verification on release downloads, slow-download auto-suggest nudge, and custom-mirror support.

**Architecture:** New `MirrorRepository` (24h-cached fetch from `/v1/mirrors/list` with bundled fallback), pure `MirrorRewriter` consumed by a Ktor `MirrorRewriteInterceptor` installed only on the GitHub-bound `HttpClient`, `ProxyManager`-owned synchronous template snapshot for the interceptor's hot path, `MultiSourceDownloader` racing direct + mirror via `coroutineScope` cancellation, `DigestVerifier` running `MessageDigest.SHA-256` after every successful download, and a Tweaks-rooted `MirrorPickerScreen` MVI alongside an app-root `AutoSuggestMirrorSheet` driven by a `SlowDownloadDetector`.

**Tech Stack:** Kotlin Multiplatform (commonMain → androidMain/jvmMain), Compose Multiplatform Material 3 (`ModalBottomSheet`, `FilterChip`, `OutlinedTextField`, `RadioButton` + `selectableGroup`), Ktor `HttpClient` + `HttpSendPipeline` interceptor + `Attributes`, kotlinx-coroutines `select` / `coroutineScope`, AndroidX DataStore Preferences, Koin singleton scope, `java.security.MessageDigest` (per-platform actuals).

**Spec:** [`docs/superpowers/specs/2026-04-29-e5-mirror-system-design.md`](../specs/2026-04-29-e5-mirror-system-design.md)
**Backend handoff:** [`roadmap/E5_CLIENT_HANDOFF.md`](../../../roadmap/E5_CLIENT_HANDOFF.md)

**Note on testing:** This codebase has no test source sets (no `commonTest`, no `kotlin.test` wiring). Per the project's plan-level decision and consistent with how the previous feedback feature shipped, no tests are added. Manual verification at every task via `:feature:tweaks:presentation:compileKotlinJvm`, `:core:domain:compileKotlinJvm`, `:core:data:compileKotlinJvm`, `:composeApp:assembleDebug`, and a final UX smoke-test in Task 19.

**Commit conventions** (per the user's global CLAUDE.md):
- Single short imperative sentence, repo-style.
- No `Co-Authored-By: Claude` trailer, no AI attribution.
- One commit per logical change.

---

## Task 1 — Domain types + bundled fallback + DataStore key constants

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/MirrorStatus.kt`
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/MirrorType.kt`
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/MirrorConfig.kt`
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/MirrorPreference.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/mirror/BundledMirrors.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/mirror/MirrorPersistence.kt`

- [ ] **Step 1: Create `MirrorStatus.kt`**

```kotlin
package zed.rainxch.core.domain.model

enum class MirrorStatus { OK, DEGRADED, DOWN, UNKNOWN }
```

- [ ] **Step 2: Create `MirrorType.kt`**

```kotlin
package zed.rainxch.core.domain.model

enum class MirrorType { OFFICIAL, COMMUNITY }
```

- [ ] **Step 3: Create `MirrorConfig.kt`**

```kotlin
package zed.rainxch.core.domain.model

import kotlinx.datetime.Instant

data class MirrorConfig(
    val id: String,
    val name: String,
    val urlTemplate: String?,
    val type: MirrorType,
    val status: MirrorStatus,
    val latencyMs: Int?,
    val lastCheckedAt: Instant?,
)
```

- [ ] **Step 4: Create `MirrorPreference.kt`**

```kotlin
package zed.rainxch.core.domain.model

sealed interface MirrorPreference {
    data object Direct : MirrorPreference

    data class Selected(val id: String) : MirrorPreference

    data class Custom(val template: String) : MirrorPreference
}
```

- [ ] **Step 5: Create `BundledMirrors.kt`**

```kotlin
package zed.rainxch.core.data.mirror

import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorStatus
import zed.rainxch.core.domain.model.MirrorType

object BundledMirrors {
    val ALL: List<MirrorConfig> =
        listOf(
            entry("direct", "Direct GitHub", null, MirrorType.OFFICIAL),
            entry("ghfast_top", "ghfast.top", "https://ghfast.top/{url}", MirrorType.COMMUNITY),
            entry("moeyy_xyz", "github.moeyy.xyz", "https://github.moeyy.xyz/{url}", MirrorType.COMMUNITY),
            entry("gh_proxy_com", "gh-proxy.com", "https://gh-proxy.com/{url}", MirrorType.COMMUNITY),
            entry("ghps_cc", "ghps.cc", "https://ghps.cc/{url}", MirrorType.COMMUNITY),
            entry("gh_99988866_xyz", "gh.api.99988866.xyz", "https://gh.api.99988866.xyz/{url}", MirrorType.COMMUNITY),
        )

    private fun entry(
        id: String,
        name: String,
        template: String?,
        type: MirrorType,
    ) = MirrorConfig(
        id = id,
        name = name,
        urlTemplate = template,
        type = type,
        status = MirrorStatus.UNKNOWN,
        latencyMs = null,
        lastCheckedAt = null,
    )
}
```

- [ ] **Step 6: Create `MirrorPersistence.kt`**

```kotlin
package zed.rainxch.core.data.mirror

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object MirrorPersistence {
    val PREFERRED_MIRROR_KEY = stringPreferencesKey("mirror_preferred_id")
    val CUSTOM_MIRROR_TEMPLATE_KEY = stringPreferencesKey("mirror_custom_template")
    val CACHED_MIRROR_LIST_JSON_KEY = stringPreferencesKey("mirror_cached_list_json")
    val CACHED_MIRROR_LIST_AT_KEY = longPreferencesKey("mirror_cached_list_at")
    val AUTO_SUGGEST_SNOOZE_UNTIL_KEY = longPreferencesKey("mirror_auto_suggest_snooze_until")
    val AUTO_SUGGEST_DISMISSED_KEY = booleanPreferencesKey("mirror_auto_suggest_dismissed")

    /** Sentinel value stored when the user picks the Custom mirror entry. */
    const val CUSTOM_MIRROR_ID_SENTINEL = "__custom__"

    /** Default sentinel — Direct GitHub. */
    const val DIRECT_MIRROR_ID = "direct"
}
```

- [ ] **Step 7: Verify it compiles**

Run: `./gradlew :core:domain:compileKotlinJvm :core:data:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/Mirror*.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/mirror/BundledMirrors.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/mirror/MirrorPersistence.kt
git commit -m "Add mirror domain types, bundled fallback list, and DataStore keys"
```

---

## Task 2 — Backend DTO + `MirrorApiClient`

**Files:**
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/MirrorListResponse.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/MirrorApiClient.kt`

`MirrorApiClient` calls the backend through the existing `BackendApiClient` (which is already configured to bypass mirror rewriting — `BackendApiClient` is constructed in `networkModule` BEFORE the `MirrorRewriteInterceptor` exists, and even after it ships, the interceptor runs only on the `GitHubClientProvider`'s client).

- [ ] **Step 1: Inspect `BackendApiClient` to confirm the request shape**

Run: `grep -n "fun get\|fun post\|HttpClient" core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/BackendApiClient.kt`

Note the public API (likely a property `client: HttpClient` or a method that exposes Ktor calls). Use whatever method `BackendApiClient` already exposes for GET requests. If it exposes `client: HttpClient` directly, call `client.get(...)` against the relative path `/v1/mirrors/list`.

- [ ] **Step 2: Create `MirrorListResponse.kt`**

```kotlin
package zed.rainxch.core.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MirrorListResponse(
    @SerialName("mirrors") val mirrors: List<MirrorEntry>,
    @SerialName("generated_at") val generatedAt: String,
)

@Serializable
data class MirrorEntry(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("url_template") val urlTemplate: String?,
    @SerialName("type") val type: String,
    @SerialName("status") val status: String,
    @SerialName("latency_ms") val latencyMs: Int? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
)
```

- [ ] **Step 3: Create `MirrorApiClient.kt`**

Pattern to use depends on what Step 1 surfaced. The simplest version assuming `BackendApiClient` exposes `httpClient: HttpClient` (or similar — adapt the call as needed):

```kotlin
package zed.rainxch.core.data.network

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import zed.rainxch.core.data.dto.MirrorListResponse
import zed.rainxch.core.data.network.BackendEndpoints.BACKEND_BASE_URL

class MirrorApiClient(
    private val backendApiClient: BackendApiClient,
) {
    /**
     * Fetches the mirror catalog from the backend. Uses the existing
     * [BackendApiClient] HttpClient — which routes through the discovery
     * proxy scope but never through `MirrorRewriteInterceptor` (the
     * interceptor lives on the GitHub-bound client only).
     */
    suspend fun fetchList(): Result<MirrorListResponse> =
        runCatching {
            backendApiClient.client
                .get("$BACKEND_BASE_URL/v1/mirrors/list")
                .body<MirrorListResponse>()
        }
}
```

If `BackendApiClient` exposes a different surface (e.g. `suspend fun <T> get(path: String): T`), adapt the call to match. **The critical invariant: this fetch must never be rewritten by `MirrorRewriteInterceptor`**, which is true as long as we use `BackendApiClient`'s client (it doesn't install the interceptor).

If `BackendEndpoints.BACKEND_BASE_URL` doesn't exist as referenced, locate the constant — `grep -rn "BACKEND_BASE_URL\|BACKEND_ORIGIN" core/data/src/commonMain` — and use whichever exact symbol is present.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/MirrorListResponse.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/MirrorApiClient.kt
git commit -m "Add MirrorListResponse DTO and MirrorApiClient backed by BackendApiClient"
```

---

## Task 3 — `MirrorRepository` interface

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/repository/MirrorRepository.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package zed.rainxch.core.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorPreference

interface MirrorRepository {
    /**
     * Emits the cached catalog immediately, then fresh entries on each
     * successful refresh. Falls back to the bundled list when the cache
     * is empty and the backend is unreachable.
     */
    fun observeCatalog(): Flow<List<MirrorConfig>>

    /** Forces a backend fetch ignoring the 24h cache. */
    suspend fun refreshCatalog(): Result<Unit>

    fun observePreference(): Flow<MirrorPreference>

    suspend fun setPreference(pref: MirrorPreference)

    /**
     * Emits a one-shot notice when the user's previously-selected mirror
     * disappears from a freshly-fetched catalog and the repository
     * auto-falls-back to Direct. UI surfaces a toast.
     */
    fun observeRemovedNotices(): Flow<MirrorRemoved>
}

// Note: synchronous "current template" reads live on ProxyManager
// (see Task 8). Keeping that out of the repository interface avoids
// spreading the snapshot responsibility across two layers.

data class MirrorRemoved(
    val displayName: String,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core:domain:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/repository/MirrorRepository.kt
git commit -m "Define MirrorRepository contract for catalog, preference, and removed-notice flows"
```

---

## Task 4 — `MirrorRepositoryImpl` + Koin registration

**Files:**
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/mirror/MirrorRepositoryImpl.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`

- [ ] **Step 1: Create `MirrorRepositoryImpl.kt`**

```kotlin
package zed.rainxch.core.data.mirror

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import zed.rainxch.core.data.dto.MirrorEntry
import zed.rainxch.core.data.dto.MirrorListResponse
import zed.rainxch.core.data.network.MirrorApiClient
import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorPreference
import zed.rainxch.core.domain.model.MirrorStatus
import zed.rainxch.core.domain.model.MirrorType
import zed.rainxch.core.domain.repository.MirrorRemoved
import zed.rainxch.core.domain.repository.MirrorRepository

class MirrorRepositoryImpl(
    private val preferences: DataStore<Preferences>,
    private val apiClient: MirrorApiClient,
    private val appScope: CoroutineScope,
) : MirrorRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheTtlMs = 24L * 60 * 60 * 1000

    private val _catalog = MutableStateFlow<List<MirrorConfig>>(emptyList())
    private val _removedNotices =
        MutableSharedFlow<MirrorRemoved>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        appScope.launch {
            // Seed the catalog flow from cache (or bundled fallback) so
            // first subscribers see something immediately.
            _catalog.value = readCachedCatalogOrBundled()
            // Then kick off a refresh if the cache is older than 24h.
            val cachedAt = preferences.data.first()[MirrorPersistence.CACHED_MIRROR_LIST_AT_KEY] ?: 0L
            if (Clock.System.now().toEpochMilliseconds() - cachedAt > cacheTtlMs) {
                refreshCatalog()
            }
        }
    }

    override fun observeCatalog(): Flow<List<MirrorConfig>> = _catalog.asStateFlow()

    override suspend fun refreshCatalog(): Result<Unit> =
        apiClient
            .fetchList()
            .onSuccess { response ->
                val configs = response.mirrors.map { it.toDomain() }
                _catalog.value = configs
                preferences.edit { prefs ->
                    prefs[MirrorPersistence.CACHED_MIRROR_LIST_JSON_KEY] = json.encodeToString(MirrorListResponse.serializer(), response)
                    prefs[MirrorPersistence.CACHED_MIRROR_LIST_AT_KEY] = Clock.System.now().toEpochMilliseconds()
                }
                checkSelectedMirrorStillExists(configs)
            }.map { }

    override fun observePreference(): Flow<MirrorPreference> =
        preferences.data.map { prefs ->
            val id = prefs[MirrorPersistence.PREFERRED_MIRROR_KEY] ?: MirrorPersistence.DIRECT_MIRROR_ID
            when (id) {
                MirrorPersistence.DIRECT_MIRROR_ID -> MirrorPreference.Direct
                MirrorPersistence.CUSTOM_MIRROR_ID_SENTINEL -> {
                    val template = prefs[MirrorPersistence.CUSTOM_MIRROR_TEMPLATE_KEY].orEmpty()
                    if (template.isBlank()) MirrorPreference.Direct else MirrorPreference.Custom(template)
                }
                else -> MirrorPreference.Selected(id)
            }
        }

    override suspend fun setPreference(pref: MirrorPreference) {
        preferences.edit { prefs ->
            when (pref) {
                MirrorPreference.Direct -> {
                    prefs[MirrorPersistence.PREFERRED_MIRROR_KEY] = MirrorPersistence.DIRECT_MIRROR_ID
                    prefs.remove(MirrorPersistence.CUSTOM_MIRROR_TEMPLATE_KEY)
                }
                is MirrorPreference.Selected -> {
                    prefs[MirrorPersistence.PREFERRED_MIRROR_KEY] = pref.id
                    prefs.remove(MirrorPersistence.CUSTOM_MIRROR_TEMPLATE_KEY)
                }
                is MirrorPreference.Custom -> {
                    prefs[MirrorPersistence.PREFERRED_MIRROR_KEY] = MirrorPersistence.CUSTOM_MIRROR_ID_SENTINEL
                    prefs[MirrorPersistence.CUSTOM_MIRROR_TEMPLATE_KEY] = pref.template
                }
            }
        }
    }

    override fun observeRemovedNotices(): Flow<MirrorRemoved> = _removedNotices.asSharedFlow()

    private suspend fun readCachedCatalogOrBundled(): List<MirrorConfig> {
        val cachedJson = preferences.data.first()[MirrorPersistence.CACHED_MIRROR_LIST_JSON_KEY]
        return if (cachedJson.isNullOrBlank()) {
            BundledMirrors.ALL
        } else {
            runCatching {
                json.decodeFromString(MirrorListResponse.serializer(), cachedJson).mirrors.map { it.toDomain() }
            }.getOrElse { BundledMirrors.ALL }
        }
    }

    private suspend fun checkSelectedMirrorStillExists(fresh: List<MirrorConfig>) {
        val pref = observePreference().first()
        if (pref !is MirrorPreference.Selected) return
        val match = fresh.firstOrNull { it.id == pref.id }
        if (match == null) {
            // Find the previous name from the cache for the toast message
            val previousName =
                _catalog.value.firstOrNull { it.id == pref.id }?.name ?: pref.id
            setPreference(MirrorPreference.Direct)
            _removedNotices.tryEmit(MirrorRemoved(displayName = previousName))
        }
    }

    private fun MirrorEntry.toDomain(): MirrorConfig =
        MirrorConfig(
            id = id,
            name = name,
            urlTemplate = urlTemplate,
            type =
                when (type) {
                    "official" -> MirrorType.OFFICIAL
                    else -> MirrorType.COMMUNITY
                },
            status =
                when (status) {
                    "ok" -> MirrorStatus.OK
                    "degraded" -> MirrorStatus.DEGRADED
                    "down" -> MirrorStatus.DOWN
                    else -> MirrorStatus.UNKNOWN
                },
            latencyMs = latencyMs,
            lastCheckedAt = lastCheckedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
}
```

The `currentTemplate()` returns `null` here intentionally — Task 8 wires the synchronous fast-path through `ProxyManager.currentMirrorTemplate()` which is backed by an `AtomicReference` synced from this repo's `observePreference()` collector.

- [ ] **Step 2: Register in `coreModule`**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`. Add these imports near the existing repository imports:

```kotlin
import zed.rainxch.core.data.mirror.MirrorRepositoryImpl
import zed.rainxch.core.data.network.MirrorApiClient
import zed.rainxch.core.domain.repository.MirrorRepository
```

Inside `coreModule`, after `single<TweaksRepository> { ... }` (around line 122) add:

```kotlin
        single<MirrorApiClient> {
            MirrorApiClient(
                backendApiClient = get(),
            )
        }

        single<MirrorRepository> {
            MirrorRepositoryImpl(
                preferences = get(),
                apiClient = get(),
                appScope = get(),
            )
        }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/commonMain/kotlin/zed/rainxch/core/data/mirror/MirrorRepositoryImpl.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt
git commit -m "Add MirrorRepositoryImpl with 24h cache, bundled fallback, and removed-notice flow"
```

---

## Task 5 — Add string resources

**Files:**
- Modify: `core/presentation/src/commonMain/composeResources/values/strings.xml`

Adds the ~25 keys the picker, custom-mirror dialog, auto-suggest sheet, and Tweaks entry need. English only — locale-specific files are intentionally NOT updated; runtime falls back to English. (Same precedent as the previous feedback feature.)

- [ ] **Step 1: Append a section before `</resources>`**

Open `core/presentation/src/commonMain/composeResources/values/strings.xml` and append this block immediately before the closing `</resources>`:

```xml
    <!-- Tweaks feature - Mirror -->
    <string name="mirror_tweaks_entry_label">Download Mirror</string>
    <string name="mirror_picker_title">Download Mirror</string>
    <string name="mirror_picker_description">Used for downloading release assets and proxying GitHub API calls. Most users should leave this on Direct GitHub.</string>

    <!-- Mirror - Sections -->
    <string name="mirror_section_official">Official</string>
    <string name="mirror_section_community">Community</string>

    <!-- Mirror - Statuses -->
    <string name="mirror_status_ok">%1$dms</string>
    <string name="mirror_status_degraded">%1$dms</string>
    <string name="mirror_status_down">(down)</string>
    <string name="mirror_status_unknown">?</string>

    <!-- Mirror - Custom -->
    <string name="mirror_custom_label">Custom mirror…</string>
    <string name="mirror_custom_dialog_title">Custom mirror</string>
    <string name="mirror_custom_dialog_hint">https://your-mirror.example/{url}</string>
    <string name="mirror_custom_validation_https">Template must start with https://</string>
    <string name="mirror_custom_validation_template">Template must contain {url} exactly once</string>
    <string name="mirror_custom_save">Save</string>

    <!-- Mirror - Test connection -->
    <string name="mirror_test_button">Test selected</string>
    <string name="mirror_test_in_progress">Testing…</string>
    <string name="mirror_test_success">Reached in %1$dms</string>
    <string name="mirror_test_http_error">Mirror returned %1$d</string>
    <string name="mirror_test_timeout">Timed out after 5s</string>
    <string name="mirror_test_dns_fail">Could not resolve host</string>
    <string name="mirror_test_other">Failed: %1$s</string>

    <!-- Mirror - Footer hint -->
    <string name="mirror_deploy_your_own_hint">All mirrors broken? You can host your own in 5 minutes — see docs.</string>

    <!-- Mirror - Removed notice + verification -->
    <string name="mirror_removed_toast">%1$s is no longer available, switched to Direct GitHub.</string>
    <string name="mirror_digest_mismatch_error">Checksum mismatch — file may have been tampered with</string>

    <!-- Mirror - Auto-suggest -->
    <string name="mirror_auto_suggest_title">Try a faster mirror?</string>
    <string name="mirror_auto_suggest_body">Some users on slow networks have better luck with a community proxy.</string>
    <string name="mirror_auto_suggest_pick_one">Pick one</string>
    <string name="mirror_auto_suggest_maybe_later">Maybe later</string>
    <string name="mirror_auto_suggest_dont_ask_again">Don\'t ask again</string>
```

Match the file's existing 4-space indentation. Apostrophe is escaped as `\'`. The `&` entity is not needed in any of these.

- [ ] **Step 2: Verify resource generation**

Run: `./gradlew :core:presentation:generateComposeResClass`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/presentation/src/commonMain/composeResources/values/strings.xml
git commit -m "Add string resources for mirror picker, dialog, auto-suggest, and verification"
```

---

## Task 6 — Asset digest plumbing

**Files:**
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/AssetNetwork.kt`
- Modify: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/GithubAsset.kt`
- Modify: any asset mapper(s) under `core/data/src/commonMain/kotlin/zed/rainxch/core/data/mappers/` that translate `AssetNetwork` → `GithubAsset`

- [ ] **Step 1: Add `digest` to `AssetNetwork.kt`**

Replace the existing data class with:

```kotlin
package zed.rainxch.core.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssetNetwork(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size") val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("uploader") val uploader: OwnerNetwork? = null,
    @SerialName("download_count") val downloadCount: Long = 0,
    @SerialName("digest") val digest: String? = null,
)
```

- [ ] **Step 2: Add `digest` to `GithubAsset.kt`**

Run: `cat core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/GithubAsset.kt`

Add `val digest: String? = null` as the last constructor parameter (default null preserves source compatibility for `copy()` callers). For example, if the file currently reads:

```kotlin
data class GithubAsset(
    val id: Long,
    val name: String,
    val contentType: String,
    val size: Long,
    val downloadUrl: String,
    val downloadCount: Long,
)
```

It becomes:

```kotlin
data class GithubAsset(
    val id: Long,
    val name: String,
    val contentType: String,
    val size: Long,
    val downloadUrl: String,
    val downloadCount: Long,
    val digest: String? = null,
)
```

- [ ] **Step 3: Update the mapper**

Run: `grep -rn "fun.*toGithubAsset\|fun.*AssetNetwork.*to.*GithubAsset\|AssetNetwork.*GithubAsset" core/data/src/commonMain --include="*.kt"`

Locate the function that maps `AssetNetwork` → `GithubAsset`. Add `digest = digest` to the constructor call (or `digest = it.digest` depending on the receiver style). Example fix shape:

```kotlin
fun AssetNetwork.toGithubAsset(): GithubAsset =
    GithubAsset(
        id = id,
        name = name,
        contentType = contentType,
        size = size,
        downloadUrl = downloadUrl,
        downloadCount = downloadCount,
        digest = digest,
    )
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm :core:domain:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/commonMain/kotlin/zed/rainxch/core/data/dto/AssetNetwork.kt \
        core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/GithubAsset.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/mappers/
git commit -m "Propagate GitHub asset digest through DTO, domain model, and mapper"
```

---

## Task 7 — `MirrorRewriter` pure helper

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/MirrorRewriter.kt`

Pure object — no Compose, no coroutines, no Koin. Trivially callable from both the Ktor interceptor and the multi-source race.

- [ ] **Step 1: Create the file**

```kotlin
package zed.rainxch.core.domain.network

import io.ktor.http.Url

object MirrorRewriter {
    private val rewriteHosts =
        setOf(
            "github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
        )

    /**
     * True iff the URL host is one of the GitHub-owned hosts that should
     * be routed through a community mirror. Returns false for all other
     * hosts including `api.github-store.org` (our backend).
     */
    fun shouldRewrite(url: String): Boolean =
        runCatching {
            val host = Url(url).host.lowercase()
            host in rewriteHosts
        }.getOrDefault(false)

    /**
     * Substitutes the literal `{url}` in the template with the full
     * GitHub URL. Caller is responsible for ensuring the template
     * contains exactly one `{url}` placeholder; that validation happens
     * at custom-mirror entry time.
     */
    fun applyTemplate(
        template: String,
        githubUrl: String,
    ): String = template.replace("{url}", githubUrl)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core:domain:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/MirrorRewriter.kt
git commit -m "Add MirrorRewriter pure helper for host check and template substitution"
```

---

## Task 8 — `ProxyManager.currentMirrorTemplate()` with synced atomic snapshot

**Files:**
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/ProxyManager.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`

`ProxyManager` is currently an `object` with in-memory per-scope flows. Extending it to know about the mirror template lets the Ktor interceptor read the current template synchronously without suspend / blocking.

- [ ] **Step 1: Replace `ProxyManager.kt`**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/ProxyManager.kt`. Replace the entire file with:

```kotlin
package zed.rainxch.core.data.network

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import zed.rainxch.core.domain.model.MirrorPreference
import zed.rainxch.core.domain.model.ProxyConfig
import zed.rainxch.core.domain.model.ProxyScope
import zed.rainxch.core.domain.repository.MirrorRepository

/**
 * Live in-memory cache of the three per-scope proxy configurations
 * **and** the resolved mirror URL template. Writers (the proxy
 * repository and the mirror collector) push updates here; consumers
 * (HTTP clients, the MirrorRewriteInterceptor) read synchronously
 * via [configFlow] / [currentMirrorTemplate].
 */
object ProxyManager {
    private val flows: Map<ProxyScope, MutableStateFlow<ProxyConfig>> =
        ProxyScope.entries.associateWith { MutableStateFlow<ProxyConfig>(ProxyConfig.System) }

    private val mirrorTemplate = atomic<String?>(null)
    private var mirrorCollectorJob: Job? = null

    fun configFlow(scope: ProxyScope): StateFlow<ProxyConfig> = flows.getValue(scope).asStateFlow()

    fun currentConfig(scope: ProxyScope): ProxyConfig = flows.getValue(scope).value

    fun setConfig(
        scope: ProxyScope,
        config: ProxyConfig,
    ) {
        flows.getValue(scope).value = config
    }

    /**
     * Effective mirror template for the current preference, or null
     * when Direct. Read by [MirrorRewriteInterceptor] on every outbound
     * GitHub request — must be hot-path safe (atomic, no I/O).
     */
    fun currentMirrorTemplate(): String? = mirrorTemplate.value

    /**
     * Starts a long-lived collector that mirrors [MirrorRepository.observePreference]
     * into the atomic snapshot used by [currentMirrorTemplate]. Idempotent —
     * subsequent calls are no-ops as long as the previous job is alive.
     *
     * Looks up the catalog via [MirrorRepository.observeCatalog] to resolve
     * `Selected(id)` → template string. If the catalog is empty (cold start
     * before bundled fallback emits) the template stays null until the
     * first emission lands.
     */
    fun startMirrorCollector(
        repository: MirrorRepository,
        scope: CoroutineScope,
    ) {
        if (mirrorCollectorJob?.isActive == true) return
        mirrorCollectorJob =
            scope.launch {
                kotlinx.coroutines.flow
                    .combine(
                        repository.observePreference(),
                        repository.observeCatalog(),
                    ) { pref, catalog ->
                        when (pref) {
                            MirrorPreference.Direct -> null
                            is MirrorPreference.Custom -> pref.template
                            is MirrorPreference.Selected ->
                                catalog.firstOrNull { it.id == pref.id }?.urlTemplate
                        }
                    }.collect { template ->
                        mirrorTemplate.value = template
                    }
            }
    }
}
```

The `kotlinx.atomicfu.atomic` import requires `atomicfu` on the classpath. Verify:

Run: `grep "atomicfu" gradle/libs.versions.toml`
- If present, the import works.
- If absent, fall back to `java.util.concurrent.atomic.AtomicReference<String?>`. Replace `private val mirrorTemplate = atomic<String?>(null)` with `private val mirrorTemplate = AtomicReference<String?>(null)`, change `.value` reads to `.get()`, and writes to `.set(...)`.

- [ ] **Step 2: Wire the collector startup in `coreModule`**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`. Replace the `MirrorRepository` registration block (added in Task 4 step 2) with:

```kotlin
        single<MirrorRepository> {
            val repo =
                MirrorRepositoryImpl(
                    preferences = get(),
                    apiClient = get(),
                    appScope = get(),
                )
            // Kick off the ProxyManager mirror-template snapshot collector
            // so the Ktor interceptor can resolve the template synchronously.
            ProxyManager.startMirrorCollector(repo, get())
            repo
        }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/ProxyManager.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt
git commit -m "Snapshot the resolved mirror template into ProxyManager for hot-path interceptor reads"
```

---

## Task 9 — `MirrorRewriteInterceptor` + `HttpClientFactory` wiring

**Files:**
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/MirrorRewriteInterceptor.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/HttpClientFactory.kt`

- [ ] **Step 1: Create `MirrorRewriteInterceptor.kt`**

```kotlin
package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import zed.rainxch.core.domain.network.MirrorRewriter

/**
 * Marks a request to bypass [MirrorRewriteInterceptor] — used by the
 * direct branch of the multi-source download race.
 */
val NO_MIRROR_REWRITE: AttributeKey<Boolean> = AttributeKey("NoMirrorRewrite")

/**
 * Installs the mirror-rewrite hook on a Ktor [HttpClient]. Call after
 * the client is built but before any request is fired.
 *
 * The hook checks (in order):
 *  1. `NO_MIRROR_REWRITE` attribute — bypass if true.
 *  2. [MirrorRewriter.shouldRewrite] — only rewrite GitHub-owned hosts.
 *  3. [ProxyManager.currentMirrorTemplate] — only rewrite when a
 *     non-Direct preference resolves to a non-null template.
 */
fun HttpClient.installMirrorRewrite() {
    plugin(HttpSend).intercept { request ->
        if (!request.attributes.contains(NO_MIRROR_REWRITE)) {
            val original = request.url.buildString()
            if (MirrorRewriter.shouldRewrite(original)) {
                val template = ProxyManager.currentMirrorTemplate()
                if (template != null) {
                    val rewritten = MirrorRewriter.applyTemplate(template, original)
                    request.url.takeFrom(Url(rewritten))
                }
            }
        }
        execute(request)
    }
}
```

- [ ] **Step 2: Wire into `HttpClientFactory.kt`**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/HttpClientFactory.kt`. Locate the `createGitHubHttpClient` function. After the `defaultRequest { ... }` block closes and before the function returns, the client is constructed. Currently the function ends with the `.config { ... }` block returning the client. Append `.also { it.installMirrorRewrite() }` to the chain so the call site looks like:

```kotlin
return createPlatformHttpClient(proxyConfig).config {
    // ... existing installs ...
}.also { it.installMirrorRewrite() }
```

Add the import: `import zed.rainxch.core.data.network.installMirrorRewrite`.

Also locate the `install(HttpRequestRetry) { ... }` block and confirm there's no `HttpRedirect` plugin already installed. If absent, add this install before `expectSuccess = false`:

```kotlin
        install(HttpRedirect) {
            checkHttpMethod = false
        }
```

Add the import: `import io.ktor.client.plugins.HttpRedirect`.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/MirrorRewriteInterceptor.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/HttpClientFactory.kt
git commit -m "Install MirrorRewriteInterceptor on the GitHub HttpClient with NO_MIRROR_REWRITE bypass"
```

---

## Task 10 — `DigestVerifier` interface + per-platform impls + Koin registration

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/DigestVerifier.kt`
- Create: `core/data/src/androidMain/kotlin/zed/rainxch/core/data/network/AndroidDigestVerifier.kt`
- Create: `core/data/src/jvmMain/kotlin/zed/rainxch/core/data/network/DesktopDigestVerifier.kt`
- Modify: `core/data/src/androidMain/kotlin/zed/rainxch/core/data/di/PlatformModule.android.kt`
- Modify: `core/data/src/jvmMain/kotlin/zed/rainxch/core/data/di/PlatformModule.jvm.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package zed.rainxch.core.domain.network

interface DigestVerifier {
    /**
     * Streams the file at [filePath] through SHA-256 and compares against
     * [expectedDigest] (which may carry a `sha256:` prefix or be raw hex).
     *
     * @return null on match, a non-null human-readable reason on
     *   mismatch / IO error.
     */
    suspend fun verify(
        filePath: String,
        expectedDigest: String,
    ): String?
}
```

- [ ] **Step 2: Create `AndroidDigestVerifier.kt`**

```kotlin
package zed.rainxch.core.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zed.rainxch.core.domain.network.DigestVerifier
import java.io.File
import java.security.MessageDigest

class AndroidDigestVerifier : DigestVerifier {
    override suspend fun verify(
        filePath: String,
        expectedDigest: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@withContext "File not found: $filePath"
            val expected = expectedDigest.removePrefix("sha256:").lowercase()
            val actual =
                runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { stream ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val read = stream.read(buf)
                            if (read <= 0) break
                            digest.update(buf, 0, read)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }.getOrElse { return@withContext "Digest computation failed: ${it.message}" }
            if (actual == expected) null else "Digest mismatch (expected $expected, got $actual)"
        }
}
```

- [ ] **Step 3: Create `DesktopDigestVerifier.kt`**

Identical body to `AndroidDigestVerifier`, just a different class name and package declaration:

```kotlin
package zed.rainxch.core.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zed.rainxch.core.domain.network.DigestVerifier
import java.io.File
import java.security.MessageDigest

class DesktopDigestVerifier : DigestVerifier {
    override suspend fun verify(
        filePath: String,
        expectedDigest: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@withContext "File not found: $filePath"
            val expected = expectedDigest.removePrefix("sha256:").lowercase()
            val actual =
                runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { stream ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val read = stream.read(buf)
                            if (read <= 0) break
                            digest.update(buf, 0, read)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }.getOrElse { return@withContext "Digest computation failed: ${it.message}" }
            if (actual == expected) null else "Digest mismatch (expected $expected, got $actual)"
        }
}
```

(The two files are identical body-wise but live under different source sets — the project's pattern, mirroring `AndroidBrowserHelper` / `DesktopBrowserHelper`.)

- [ ] **Step 4: Register in `PlatformModule.android.kt`**

Open `core/data/src/androidMain/kotlin/zed/rainxch/core/data/di/PlatformModule.android.kt`. Add the import:

```kotlin
import zed.rainxch.core.data.network.AndroidDigestVerifier
import zed.rainxch.core.domain.network.DigestVerifier
```

Inside the `module { ... }` block, near the existing `single<BrowserHelper> { ... }` registration (around line 148), add:

```kotlin
        single<DigestVerifier> {
            AndroidDigestVerifier()
        }
```

- [ ] **Step 5: Register in `PlatformModule.jvm.kt`**

Open `core/data/src/jvmMain/kotlin/zed/rainxch/core/data/di/PlatformModule.jvm.kt`. Add the import:

```kotlin
import zed.rainxch.core.data.network.DesktopDigestVerifier
import zed.rainxch.core.domain.network.DigestVerifier
```

Inside the `module { ... }` block, near the `single<BrowserHelper> { ... }` registration (around line 85), add:

```kotlin
        single<DigestVerifier> {
            DesktopDigestVerifier()
        }
```

- [ ] **Step 6: Verify it compiles for both targets**

Run: `./gradlew :core:data:compileKotlinJvm :core:data:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/DigestVerifier.kt \
        core/data/src/androidMain/kotlin/zed/rainxch/core/data/network/AndroidDigestVerifier.kt \
        core/data/src/jvmMain/kotlin/zed/rainxch/core/data/network/DesktopDigestVerifier.kt \
        core/data/src/androidMain/kotlin/zed/rainxch/core/data/di/PlatformModule.android.kt \
        core/data/src/jvmMain/kotlin/zed/rainxch/core/data/di/PlatformModule.jvm.kt
git commit -m "Add streaming SHA-256 DigestVerifier for Android and Desktop"
```

---

## Task 11 — `MultiSourceDownloader` interface + impl

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/system/MultiSourceDownloader.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/MultiSourceDownloaderImpl.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`

`MultiSourceDownloader` provides a `download(githubUrl, fileName) → Flow<DownloadProgress>` API. When a non-null mirror template is selected, it races direct + mirror. When Direct, it just delegates.

- [ ] **Step 1: Create the interface**

```kotlin
package zed.rainxch.core.domain.system

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.DownloadProgress

interface MultiSourceDownloader {
    /**
     * Downloads a release asset honouring the current mirror preference.
     *
     * - When [zed.rainxch.core.data.network.ProxyManager.currentMirrorTemplate] is null,
     *   delegates to the underlying [zed.rainxch.core.domain.network.Downloader].
     * - Otherwise launches both Direct and Mirror downloads in parallel,
     *   cancels the loser at the first valid stream, and returns the
     *   winner's progress flow.
     *
     * Only release-asset downloads should call this — API calls go
     * through the mirror-only path.
     */
    fun download(
        githubUrl: String,
        suggestedFileName: String? = null,
    ): Flow<DownloadProgress>
}
```

- [ ] **Step 2: Create `MultiSourceDownloaderImpl.kt`**

```kotlin
package zed.rainxch.core.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.domain.model.DownloadProgress
import zed.rainxch.core.domain.network.Downloader
import zed.rainxch.core.domain.network.MirrorRewriter
import zed.rainxch.core.domain.system.MultiSourceDownloader

class MultiSourceDownloaderImpl(
    private val downloader: Downloader,
) : MultiSourceDownloader {
    override fun download(
        githubUrl: String,
        suggestedFileName: String?,
    ): Flow<DownloadProgress> {
        val template = ProxyManager.currentMirrorTemplate()
        if (template == null) {
            return downloader.download(githubUrl, suggestedFileName)
        }
        val mirrorUrl = MirrorRewriter.applyTemplate(template, githubUrl)
        return raceDownloads(githubUrl, mirrorUrl, suggestedFileName)
    }

    private fun raceDownloads(
        directUrl: String,
        mirrorUrl: String,
        suggestedFileName: String?,
    ): Flow<DownloadProgress> =
        flow {
            coroutineScope {
                val winnerSignal = CompletableDeferred<String>()  // resolves with the winner label

                val directJob =
                    launch {
                        try {
                            downloader
                                .download(directUrl, suggestedFileName)
                                .collect { progress ->
                                    if (winnerSignal.complete("direct") || winnerSignal.getCompleted() == "direct") {
                                        emit(progress)
                                    } else {
                                        // Mirror won — stop emitting from direct.
                                        return@collect
                                    }
                                }
                        } catch (t: Throwable) {
                            if (!winnerSignal.isCompleted) {
                                // Ignore — let the mirror try.
                            } else {
                                throw t
                            }
                        }
                    }

                val mirrorJob =
                    launch {
                        try {
                            downloader
                                .download(mirrorUrl, suggestedFileName)
                                .collect { progress ->
                                    if (winnerSignal.complete("mirror") || winnerSignal.getCompleted() == "mirror") {
                                        emit(progress)
                                    } else {
                                        return@collect
                                    }
                                }
                        } catch (t: Throwable) {
                            if (!winnerSignal.isCompleted) {
                                // Ignore — let direct try.
                            } else {
                                throw t
                            }
                        }
                    }

                val winner = winnerSignal.await()
                if (winner == "direct") mirrorJob.cancelAndJoin() else directJob.cancelAndJoin()
            }
        }.flowOn(Dispatchers.IO)
}
```

The `flow { coroutineScope { ... } }` shape ensures both child jobs are cancelled when the consumer cancels. The winner is whichever flow first emits a `DownloadProgress` — the assumption is that `Downloader.download()` doesn't emit progress until the HTTP connection is open and bytes are arriving, which matches the existing `AndroidDownloader` / `DesktopDownloader` implementations.

**Note on `NO_MIRROR_REWRITE`:** The direct branch needs to bypass the interceptor. Since `Downloader.download(url)` doesn't currently expose a way to set Ktor request attributes, the cleanest path is to have `Downloader.download(...)` add the `NO_MIRROR_REWRITE` attribute when the URL is a `github.com`/`api.github.com`/`objects.githubusercontent.com`/`raw.githubusercontent.com` URL AND the caller is from `MultiSourceDownloaderImpl`. The simplest way to thread that:

Add an optional parameter to the existing `Downloader.download(url, fileName)` interface — `bypassMirror: Boolean = false` — that the platform impls forward as an attribute on the underlying Ktor request. The `MultiSourceDownloaderImpl` direct branch sets `bypassMirror = true`; everywhere else continues to call the two-arg form.

- [ ] **Step 3: Extend `Downloader` interface to accept `bypassMirror`**

Open `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/Downloader.kt`. Replace with:

```kotlin
package zed.rainxch.core.domain.network

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.DownloadProgress

interface Downloader {
    fun download(
        url: String,
        suggestedFileName: String? = null,
        bypassMirror: Boolean = false,
    ): Flow<DownloadProgress>

    suspend fun saveToFile(
        url: String,
        suggestedFileName: String? = null,
    ): String

    suspend fun getDownloadedFilePath(fileName: String): String?

    suspend fun cancelDownload(fileName: String): Boolean
}
```

- [ ] **Step 4: Update `AndroidDownloader.kt` and `DesktopDownloader.kt`**

In each, find the `override fun download(url: String, suggestedFileName: String?)` signature and add `bypassMirror: Boolean = false`. Wherever the request is built, add an attribute when `bypassMirror == true`. For Ktor-style HTTP calls inside the downloader, locate the `client.prepareGet(url) { ... }` or equivalent block and add:

```kotlin
if (bypassMirror) attributes.put(NO_MIRROR_REWRITE, true)
```

Add the import: `import zed.rainxch.core.data.network.NO_MIRROR_REWRITE`.

If the platform downloader doesn't use the GitHub `HttpClient` (e.g. uses Android's `DownloadManager` directly), then `bypassMirror` can be ignored on that target — `DownloadManager` doesn't go through the Ktor interceptor at all, so no bypass is needed. In that case, leave the `bypassMirror` parameter on the interface (so the call site type-checks) but don't act on it inside that platform's impl. Add a comment explaining why:

```kotlin
override fun download(
    url: String,
    suggestedFileName: String?,
    bypassMirror: Boolean,
): Flow<DownloadProgress> {
    // bypassMirror is ignored here: this downloader uses Android's
    // DownloadManager which doesn't traverse the Ktor MirrorRewriteInterceptor.
    // ...
}
```

Inspect each downloader and pick the right approach.

- [ ] **Step 5: Update `MultiSourceDownloaderImpl.kt` direct branch**

In `MultiSourceDownloaderImpl.kt`, change the direct download call to:

```kotlin
downloader.download(directUrl, suggestedFileName, bypassMirror = true)
```

- [ ] **Step 6: Register `MultiSourceDownloader` in `coreModule`**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`. Add the import:

```kotlin
import zed.rainxch.core.data.download.MultiSourceDownloaderImpl
import zed.rainxch.core.domain.system.MultiSourceDownloader
```

Inside `coreModule`, before the `DownloadOrchestrator` registration, add:

```kotlin
        single<MultiSourceDownloader> {
            MultiSourceDownloaderImpl(
                downloader = get(),
            )
        }
```

- [ ] **Step 7: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm :core:data:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/system/MultiSourceDownloader.kt \
        core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/Downloader.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/MultiSourceDownloaderImpl.kt \
        core/data/src/androidMain/kotlin/zed/rainxch/core/data/services/AndroidDownloader.kt \
        core/data/src/jvmMain/kotlin/zed/rainxch/core/data/services/DesktopDownloader.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt
git commit -m "Race direct and mirror downloads through MultiSourceDownloader with NO_MIRROR_REWRITE bypass"
```

---

## Task 12 — Wire `DigestVerifier` and `MultiSourceDownloader` into `DownloadOrchestratorImpl`

**Files:**
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadOrchestrator.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`

The orchestrator currently calls `downloader.download(url, fileName)` directly. We replace that with `multiSourceDownloader.download(url, fileName)` and add a digest-verification step after the download completes.

- [ ] **Step 1: Add the new dependencies to the orchestrator constructor**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadOrchestrator.kt`. Locate the class header. Add `multiSourceDownloader: MultiSourceDownloader` and `digestVerifier: DigestVerifier` to the primary constructor.

Add the imports:

```kotlin
import zed.rainxch.core.domain.network.DigestVerifier
import zed.rainxch.core.domain.system.MultiSourceDownloader
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_digest_mismatch_error
import org.jetbrains.compose.resources.getString
```

- [ ] **Step 2: Replace the download call**

Locate the line that calls `downloader.download(url, fileName)` (or whatever the existing call site is — search for `.download(` in the file). Replace `downloader.` with `multiSourceDownloader.`. Keep the rest of the chain identical.

- [ ] **Step 3: Add digest verification after successful download**

Locate where the download stage transitions to `Completed` / `AwaitingInstall` / installation begins (search for `DownloadStage.Installing` or `filePath = `). After the file is written to disk and BEFORE the install/completion transition, insert:

```kotlin
val asset = spec.asset
if (asset.digest != null) {
    val verifyError = digestVerifier.verify(filePath, asset.digest)
    if (verifyError != null) {
        runCatching { java.io.File(filePath).delete() }
        // Transition to Failed with the localized error message.
        update(packageName) {
            it.copy(
                stage = DownloadStage.Failed,
                errorMessage = getString(Res.string.mirror_digest_mismatch_error),
            )
        }
        return
    }
}
```

Adapt the `update(packageName) { ... }` form to whatever helper the file already uses for state mutation. If the orchestrator uses a `MutableStateFlow<Map<String, OrchestratedDownload>>` directly, the equivalent is:

```kotlin
_downloads.update { map ->
    map.toMutableMap().apply {
        this[packageName]?.let {
            this[packageName] = it.copy(
                stage = DownloadStage.Failed,
                errorMessage = getString(Res.string.mirror_digest_mismatch_error),
            )
        }
    }
}
```

Use whatever pattern is already established in the file.

The `getString()` import comes from `org.jetbrains.compose.resources` and is suspending — orchestrator code already runs inside `appScope.launch { }` blocks, so this is callable. If for some reason it isn't (e.g. the orchestrator passes through a non-suspend lambda), pass the localized error string in via DI as a `() -> String` provider, or hard-code English here as a fallback ("Checksum mismatch — file may have been tampered with"). Inspect the file before deciding.

If `asset.digest == null`, log via Kermit and skip:

```kotlin
} else {
    logger.i { "No digest for ${asset.name}, skipping SHA-256 verification" }
}
```

(`logger` is the `GitHubStoreLogger` already injected into many of these sites — verify by checking the file's imports.)

- [ ] **Step 4: Update Koin registration**

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`. Locate the `DownloadOrchestrator` registration (around line 226):

```kotlin
        single<DownloadOrchestrator> {
            DefaultDownloadOrchestrator(
                downloader = get(),
                installer = get(),
                installedAppsRepository = get(),
                pendingInstallNotifier = get(),
                appScope = get(),
            )
        }
```

Add `multiSourceDownloader = get()` and `digestVerifier = get()` to the constructor call. The `downloader` parameter is no longer needed if all download calls now route through `multiSourceDownloader`; remove it from both the registration and the class constructor. Final shape:

```kotlin
        single<DownloadOrchestrator> {
            DefaultDownloadOrchestrator(
                multiSourceDownloader = get(),
                digestVerifier = get(),
                installer = get(),
                installedAppsRepository = get(),
                pendingInstallNotifier = get(),
                appScope = get(),
            )
        }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm :core:data:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadOrchestrator.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt
git commit -m "Route asset downloads through MultiSourceDownloader and verify SHA-256 after completion"
```

---

## Task 13 — `SlowDownloadDetector` interface + impl + wiring

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/SlowDownloadDetector.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/SlowDownloadDetectorImpl.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadOrchestrator.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package zed.rainxch.core.domain.network

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.DownloadProgress

interface SlowDownloadDetector {
    /**
     * Emits Unit when the user should be prompted to try a faster mirror.
     * Conditions: 3+ sustained-slow events (<100KB/s for 30s) within a
     * 10-minute rolling window AND the current mirror preference is Direct
     * AND auto-suggest is not snoozed/dismissed.
     */
    val suggestMirror: Flow<Unit>

    /**
     * Observe a download's progress. Side-effecting — the detector
     * updates internal state on every emission and may emit a suggestion.
     */
    fun observe(progress: Flow<DownloadProgress>)
}
```

- [ ] **Step 2: Create the impl**

```kotlin
package zed.rainxch.core.data.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import zed.rainxch.core.data.mirror.MirrorPersistence
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.domain.model.DownloadProgress
import zed.rainxch.core.domain.network.SlowDownloadDetector

class SlowDownloadDetectorImpl(
    private val preferences: DataStore<Preferences>,
    private val appScope: CoroutineScope,
) : SlowDownloadDetector {
    private val windowMs = 10L * 60 * 1000
    private val sustainedMs = 30L * 1000
    private val thresholdBytesPerSec = 100L * 1024
    private val triggerCount = 3

    private val recentSlowEvents: ArrayDeque<Long> = ArrayDeque()
    private val _suggestMirror =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val suggestMirror: Flow<Unit> = _suggestMirror.asSharedFlow()

    override fun observe(progress: Flow<DownloadProgress>) {
        appScope.launch {
            // Bytes-per-second window: collect (timestampMs, bytes) samples.
            // When 30s of samples averages below threshold, record a slow event.
            val samples = ArrayDeque<Pair<Long, Long>>()
            progress.collect { p ->
                val now = Clock.System.now().toEpochMilliseconds()
                samples.addLast(now to p.bytesDownloaded)
                while (samples.isNotEmpty() && samples.first().first < now - sustainedMs) {
                    samples.removeFirst()
                }
                if (samples.size >= 2) {
                    val first = samples.first()
                    val last = samples.last()
                    val elapsedSec = (last.first - first.first).coerceAtLeast(1L) / 1000.0
                    val deltaBytes = (last.second - first.second).coerceAtLeast(0L)
                    val bytesPerSec = (deltaBytes / elapsedSec).toLong()
                    val windowFull = (last.first - first.first) >= sustainedMs - 500
                    if (windowFull && bytesPerSec < thresholdBytesPerSec) {
                        recordSlowEvent(now)
                    }
                }
            }
        }
    }

    private suspend fun recordSlowEvent(timestampMs: Long) {
        // Add this event, prune old ones.
        recentSlowEvents.addLast(timestampMs)
        while (recentSlowEvents.isNotEmpty() && recentSlowEvents.first() < timestampMs - windowMs) {
            recentSlowEvents.removeFirst()
        }
        if (recentSlowEvents.size < triggerCount) return

        // Check gates: must be Direct, not snoozed, not dismissed.
        if (ProxyManager.currentMirrorTemplate() != null) return
        val prefs = preferences.data.first()
        if (prefs[MirrorPersistence.AUTO_SUGGEST_DISMISSED_KEY] == true) return
        val snoozeUntil = prefs[MirrorPersistence.AUTO_SUGGEST_SNOOZE_UNTIL_KEY] ?: 0L
        if (snoozeUntil > timestampMs) return

        recentSlowEvents.clear()
        _suggestMirror.tryEmit(Unit)
    }
}
```

- [ ] **Step 3: Wire it into the orchestrator**

Open `DefaultDownloadOrchestrator.kt`. Add `slowDownloadDetector: SlowDownloadDetector` to the constructor. Wherever the download flow is collected (the same site changed in Task 12), call `slowDownloadDetector.observe(downloadFlow)` BEFORE the `.collect { ... }` that drives the orchestrator's own progress tracking. Use the `Flow.shareIn` pattern if needed so the same flow can be both observed by the detector and consumed by the orchestrator:

```kotlin
val sharedFlow = multiSourceDownloader
    .download(asset.downloadUrl, fileName)
    .shareIn(appScope, SharingStarted.Eagerly, replay = 1)
slowDownloadDetector.observe(sharedFlow)
sharedFlow.collect { progress -> /* existing logic */ }
```

If `shareIn` complicates things, simpler: tee the flow with `onEach { p -> slowDownloadDetector.observeOne(p) }` and have the detector accept individual progress samples instead of a flow. Either works — pick whichever fits the file's existing style. The detector interface above expects a flow; if you pick the tee approach, change the interface signature to `fun observeOne(p: DownloadProgress)` and adapt the impl.

- [ ] **Step 4: Register in `coreModule`**

```kotlin
import zed.rainxch.core.data.download.SlowDownloadDetectorImpl
import zed.rainxch.core.domain.network.SlowDownloadDetector
```

```kotlin
        single<SlowDownloadDetector> {
            SlowDownloadDetectorImpl(
                preferences = get(),
                appScope = get(),
            )
        }
```

Add `slowDownloadDetector = get()` to the `DownloadOrchestrator` registration.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :core:data:compileKotlinJvm :core:data:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/network/SlowDownloadDetector.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/SlowDownloadDetectorImpl.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadOrchestrator.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt
git commit -m "Detect sustained slow downloads to drive the mirror auto-suggest nudge"
```

---

## Task 14 — Auto-suggest sheet + ViewModel + app-root mounting

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/AutoSuggestMirrorViewModel.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/AutoSuggestMirrorSheet.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`

The sheet must be mounted at app-root so any download from any screen can trigger it.

- [ ] **Step 1: Create `AutoSuggestMirrorViewModel.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import zed.rainxch.core.data.mirror.MirrorPersistence
import zed.rainxch.core.domain.network.SlowDownloadDetector

class AutoSuggestMirrorViewModel(
    private val detector: SlowDownloadDetector,
    private val preferences: DataStore<Preferences>,
) : ViewModel() {
    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    init {
        viewModelScope.launch {
            detector.suggestMirror.collect {
                _isVisible.value = true
            }
        }
    }

    fun onMaybeLater() {
        _isVisible.value = false
        viewModelScope.launch {
            preferences.edit { prefs ->
                prefs[MirrorPersistence.AUTO_SUGGEST_SNOOZE_UNTIL_KEY] =
                    Clock.System.now().toEpochMilliseconds() + 24L * 60 * 60 * 1000
            }
        }
    }

    fun onDontAskAgain() {
        _isVisible.value = false
        viewModelScope.launch {
            preferences.edit { prefs ->
                prefs[MirrorPersistence.AUTO_SUGGEST_DISMISSED_KEY] = true
            }
        }
    }

    fun onPickOneClicked() {
        _isVisible.value = false
        // Navigation handled by the host composable.
    }

    fun dismiss() {
        _isVisible.value = false
    }
}
```

- [ ] **Step 2: Create `AutoSuggestMirrorSheet.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_body
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_dont_ask_again
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_maybe_later
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_pick_one
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSuggestMirrorSheet(
    onDismiss: () -> Unit,
    onPickOne: () -> Unit,
    onMaybeLater: () -> Unit,
    onDontAskAgain: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.mirror_auto_suggest_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.mirror_auto_suggest_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPickOne,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.mirror_auto_suggest_pick_one))
            }
            OutlinedButton(
                onClick = onMaybeLater,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.mirror_auto_suggest_maybe_later))
            }
            TextButton(
                onClick = onDontAskAgain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.mirror_auto_suggest_dont_ask_again))
            }
        }
    }
}
```

- [ ] **Step 3: Mount in `AppNavigation.kt`**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt`. After the `NavHost { ... }` block (or wherever the root composition allows top-level overlays), add:

```kotlin
val autoSuggestVm: AutoSuggestMirrorViewModel = koinViewModel()
val isAutoSuggestVisible by autoSuggestVm.isVisible.collectAsStateWithLifecycle()
if (isAutoSuggestVisible) {
    AutoSuggestMirrorSheet(
        onDismiss = autoSuggestVm::dismiss,
        onPickOne = {
            autoSuggestVm.onPickOneClicked()
            navController.navigate(GithubStoreGraph.MirrorPickerScreen)
        },
        onMaybeLater = autoSuggestVm::onMaybeLater,
        onDontAskAgain = autoSuggestVm::onDontAskAgain,
    )
}
```

Add the imports for `AutoSuggestMirrorViewModel`, `AutoSuggestMirrorSheet`, `koinViewModel`, `collectAsStateWithLifecycle`, and `GithubStoreGraph.MirrorPickerScreen`. The `MirrorPickerScreen` route is added in Task 18; this code will not compile until then. Stage it but don't run the build until Task 18 lands. Alternatively comment-out the navigate line for now and remove the comment in Task 18.

- [ ] **Step 4: Register `AutoSuggestMirrorViewModel` in Koin**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`. Add the import:

```kotlin
import zed.rainxch.tweaks.presentation.mirror.AutoSuggestMirrorViewModel
```

Add to the module block:

```kotlin
        viewModelOf(::AutoSuggestMirrorViewModel)
```

- [ ] **Step 5: Defer compile verification to Task 18**

The navigation reference to `GithubStoreGraph.MirrorPickerScreen` will compile after Task 18 adds the route. For now:

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL (the ViewModel + sheet alone don't depend on navigation).

- [ ] **Step 6: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/AutoSuggestMirrorViewModel.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/AutoSuggestMirrorSheet.kt \
        composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt \
        composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt
git commit -m "Add auto-suggest mirror sheet driven by SlowDownloadDetector at app root"
```

---

## Task 15 — Mirror picker MVI scaffolding (state, action, event)

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerState.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerAction.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerEvent.kt`

- [ ] **Step 1: Create `MirrorPickerState.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror

import org.jetbrains.compose.resources.StringResource
import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorPreference

data class MirrorPickerState(
    val mirrors: List<MirrorConfig> = emptyList(),
    val preference: MirrorPreference = MirrorPreference.Direct,
    val isCustomDialogVisible: Boolean = false,
    val customDraft: String = "",
    val customDraftError: StringResource? = null,
    val isTesting: Boolean = false,
    val testResult: TestResult? = null,
    val isRefreshing: Boolean = false,
)

sealed interface TestResult {
    data class Success(val latencyMs: Long) : TestResult

    data class HttpError(val code: Int) : TestResult

    data object Timeout : TestResult

    data object DnsFailure : TestResult

    data class Other(val message: String) : TestResult
}
```

- [ ] **Step 2: Create `MirrorPickerAction.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror

import zed.rainxch.core.domain.model.MirrorConfig

sealed interface MirrorPickerAction {
    data object OnNavigateBack : MirrorPickerAction

    data class OnSelectMirror(val mirror: MirrorConfig) : MirrorPickerAction

    data object OnCustomMirrorClicked : MirrorPickerAction

    data class OnCustomDraftChanged(val value: String) : MirrorPickerAction

    data object OnCustomMirrorConfirm : MirrorPickerAction

    data object OnCustomMirrorDismiss : MirrorPickerAction

    data object OnTestConnection : MirrorPickerAction

    data object OnRefreshCatalog : MirrorPickerAction

    data object OnDeployYourOwnClicked : MirrorPickerAction
}
```

- [ ] **Step 3: Create `MirrorPickerEvent.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror

sealed interface MirrorPickerEvent {
    data class MirrorRemovedNotice(val displayName: String) : MirrorPickerEvent

    data class OpenUrl(val url: String) : MirrorPickerEvent
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerState.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerAction.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerEvent.kt
git commit -m "Add MirrorPickerState, Action, and Event for the picker MVI"
```

---

## Task 16 — Mirror picker ViewModel

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

```kotlin
package zed.rainxch.tweaks.presentation.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import zed.rainxch.core.domain.model.MirrorPreference
import zed.rainxch.core.domain.network.MirrorRewriter
import zed.rainxch.core.domain.repository.MirrorRepository
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_custom_validation_https
import zed.rainxch.githubstore.core.presentation.res.mirror_custom_validation_template
import kotlin.time.TimeSource

class MirrorPickerViewModel(
    private val mirrorRepository: MirrorRepository,
    private val testHttpClient: HttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(MirrorPickerState())
    val state = _state.asStateFlow()

    private val _events = Channel<MirrorPickerEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                mirrorRepository.observeCatalog(),
                mirrorRepository.observePreference(),
            ) { catalog, pref ->
                catalog to pref
            }.collect { (catalog, pref) ->
                _state.update { it.copy(mirrors = catalog, preference = pref) }
            }
        }
        viewModelScope.launch {
            mirrorRepository.observeRemovedNotices().collect { notice ->
                _events.send(MirrorPickerEvent.MirrorRemovedNotice(notice.displayName))
            }
        }
    }

    fun onAction(action: MirrorPickerAction) {
        when (action) {
            MirrorPickerAction.OnNavigateBack -> { /* host handles via callback */ }
            is MirrorPickerAction.OnSelectMirror -> selectMirror(action.mirror)
            MirrorPickerAction.OnCustomMirrorClicked ->
                _state.update { it.copy(isCustomDialogVisible = true, customDraft = "", customDraftError = null) }
            is MirrorPickerAction.OnCustomDraftChanged -> updateDraft(action.value)
            MirrorPickerAction.OnCustomMirrorConfirm -> confirmCustom()
            MirrorPickerAction.OnCustomMirrorDismiss ->
                _state.update { it.copy(isCustomDialogVisible = false) }
            MirrorPickerAction.OnTestConnection -> runTest()
            MirrorPickerAction.OnRefreshCatalog -> refresh()
            MirrorPickerAction.OnDeployYourOwnClicked ->
                viewModelScope.launch {
                    _events.send(MirrorPickerEvent.OpenUrl("https://github.com/hunshcn/gh-proxy"))
                }
        }
    }

    private fun selectMirror(mirror: zed.rainxch.core.domain.model.MirrorConfig) {
        viewModelScope.launch {
            val pref =
                if (mirror.id == "direct") MirrorPreference.Direct else MirrorPreference.Selected(mirror.id)
            mirrorRepository.setPreference(pref)
        }
    }

    private fun updateDraft(value: String) {
        val error =
            when {
                value.isBlank() -> null
                !value.startsWith("https://") -> Res.string.mirror_custom_validation_https
                value.split("{url}").size - 1 != 1 -> Res.string.mirror_custom_validation_template
                else -> null
            }
        _state.update { it.copy(customDraft = value, customDraftError = error) }
    }

    private fun confirmCustom() {
        val draft = state.value.customDraft
        val error = state.value.customDraftError
        if (draft.isBlank() || error != null) return
        viewModelScope.launch {
            mirrorRepository.setPreference(MirrorPreference.Custom(draft))
            _state.update { it.copy(isCustomDialogVisible = false, customDraft = "", customDraftError = null) }
        }
    }

    private fun runTest() {
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null) }
            val pref = state.value.preference
            val template =
                when (pref) {
                    MirrorPreference.Direct -> null
                    is MirrorPreference.Custom -> pref.template
                    is MirrorPreference.Selected ->
                        state.value.mirrors.firstOrNull { it.id == pref.id }?.urlTemplate
                }
            val targetUrl =
                if (template == null) "https://api.github.com/zen"
                else MirrorRewriter.applyTemplate(template, "https://api.github.com/zen")
            val result =
                withTimeoutOrNull(5_000L) {
                    runCatching {
                        val mark = TimeSource.Monotonic.markNow()
                        val response = testHttpClient.get(targetUrl)
                        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
                        response.status.value to elapsedMs
                    }
                }
            val testResult: TestResult =
                when {
                    result == null -> TestResult.Timeout
                    result.isSuccess -> {
                        val (status, ms) = result.getOrThrow()
                        if (status in 200..299) TestResult.Success(ms) else TestResult.HttpError(status)
                    }
                    result.exceptionOrNull() is UnresolvedAddressException -> TestResult.DnsFailure
                    else -> TestResult.Other(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            _state.update { it.copy(isTesting = false, testResult = testResult) }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            mirrorRepository.refreshCatalog()
            _state.update { it.copy(isRefreshing = false) }
        }
    }
}
```

The `testHttpClient` is a generic Ktor client without `MirrorRewriteInterceptor` (we manually apply the template via `MirrorRewriter.applyTemplate(...)` so we test the exact URL the user expects). Register in Koin in Task 18.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerViewModel.kt
git commit -m "Add MirrorPickerViewModel with selection, custom-mirror, test, and refresh logic"
```

---

## Task 17 — Mirror picker UI components

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/MirrorRow.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/CustomMirrorDialog.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/DeployYourOwnHint.kt`
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/StatusDot.kt`

- [ ] **Step 1: Create `StatusDot.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import zed.rainxch.core.domain.model.MirrorStatus

@Composable
fun StatusDot(
    status: MirrorStatus,
    modifier: Modifier = Modifier,
) {
    val color =
        when (status) {
            MirrorStatus.OK -> MaterialTheme.colorScheme.primary
            MirrorStatus.DEGRADED -> MaterialTheme.colorScheme.tertiary
            MirrorStatus.DOWN -> MaterialTheme.colorScheme.error
            MirrorStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
        }
    Box(
        modifier =
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
    )
}
```

- [ ] **Step 2: Create `MirrorRow.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorStatus
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_status_degraded
import zed.rainxch.githubstore.core.presentation.res.mirror_status_down
import zed.rainxch.githubstore.core.presentation.res.mirror_status_ok
import zed.rainxch.githubstore.core.presentation.res.mirror_status_unknown

@Composable
fun MirrorRow(
    mirror: MirrorConfig,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        StatusDot(status = mirror.status)
        Text(
            text = mirror.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 8.dp),
        )
        val label =
            when (mirror.status) {
                MirrorStatus.OK -> mirror.latencyMs?.let { stringResource(Res.string.mirror_status_ok, it) }
                MirrorStatus.DEGRADED -> mirror.latencyMs?.let { stringResource(Res.string.mirror_status_degraded, it) }
                MirrorStatus.DOWN -> stringResource(Res.string.mirror_status_down)
                MirrorStatus.UNKNOWN -> stringResource(Res.string.mirror_status_unknown)
            }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 3: Create `CustomMirrorDialog.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.cancel
import zed.rainxch.githubstore.core.presentation.res.mirror_custom_dialog_hint
import zed.rainxch.githubstore.core.presentation.res.mirror_custom_dialog_title
import zed.rainxch.githubstore.core.presentation.res.mirror_custom_save

@Composable
fun CustomMirrorDialog(
    draft: String,
    error: StringResource?,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mirror_custom_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(Res.string.mirror_custom_dialog_hint)) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (error != null) {
                    Text(
                        text = stringResource(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = draft.isNotBlank() && error == null,
            ) {
                Text(stringResource(Res.string.mirror_custom_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
```

(`Res.string.cancel` should already exist — `grep "name=\"cancel\"" core/presentation/.../strings.xml` to confirm. If not, use any existing close-equivalent; the project uses cancel/close/dismiss strings throughout.)

- [ ] **Step 4: Create `DeployYourOwnHint.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_deploy_your_own_hint

@Composable
fun DeployYourOwnHint(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(Res.string.mirror_deploy_your_own_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp, horizontal = 12.dp),
    )
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :feature:tweaks:presentation:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/components/
git commit -m "Add mirror picker building blocks: StatusDot, MirrorRow, CustomMirrorDialog, DeployYourOwnHint"
```

---

## Task 18 — Mirror picker root + navigation route + Koin registration

**Files:**
- Create: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerRoot.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/navigation/GithubStoreGraph.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`

- [ ] **Step 1: Create `MirrorPickerRoot.kt`**

```kotlin
package zed.rainxch.tweaks.presentation.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorPreference
import zed.rainxch.core.domain.model.MirrorType
import zed.rainxch.core.presentation.utils.ObserveAsEvents
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_custom_label
import zed.rainxch.githubstore.core.presentation.res.mirror_picker_description
import zed.rainxch.githubstore.core.presentation.res.mirror_picker_title
import zed.rainxch.githubstore.core.presentation.res.mirror_removed_toast
import zed.rainxch.githubstore.core.presentation.res.mirror_section_community
import zed.rainxch.githubstore.core.presentation.res.mirror_section_official
import zed.rainxch.githubstore.core.presentation.res.mirror_test_button
import zed.rainxch.githubstore.core.presentation.res.mirror_test_dns_fail
import zed.rainxch.githubstore.core.presentation.res.mirror_test_http_error
import zed.rainxch.githubstore.core.presentation.res.mirror_test_in_progress
import zed.rainxch.githubstore.core.presentation.res.mirror_test_other
import zed.rainxch.githubstore.core.presentation.res.mirror_test_success
import zed.rainxch.githubstore.core.presentation.res.mirror_test_timeout
import zed.rainxch.tweaks.presentation.mirror.components.CustomMirrorDialog
import zed.rainxch.tweaks.presentation.mirror.components.DeployYourOwnHint
import zed.rainxch.tweaks.presentation.mirror.components.MirrorRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorPickerRoot(
    onNavigateBack: () -> Unit,
    viewModel: MirrorPickerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is MirrorPickerEvent.MirrorRemovedNotice ->
                coroutineScope.launch {
                    snackbarState.showSnackbar(getString(Res.string.mirror_removed_toast, event.displayName))
                }
            is MirrorPickerEvent.OpenUrl -> uriHandler.openUri(event.url)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.mirror_picker_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
    ) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.mirror_picker_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                Text(
                    text = stringResource(Res.string.mirror_section_official),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                items = state.mirrors.filter { it.type == MirrorType.OFFICIAL },
                key = { it.id },
            ) { mirror ->
                MirrorRow(
                    mirror = mirror,
                    selected = isMirrorSelected(mirror, state.preference),
                    onClick = { viewModel.onAction(MirrorPickerAction.OnSelectMirror(mirror)) },
                )
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.mirror_section_community),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                items = state.mirrors.filter { it.type == MirrorType.COMMUNITY },
                key = { it.id },
            ) { mirror ->
                MirrorRow(
                    mirror = mirror,
                    selected = isMirrorSelected(mirror, state.preference),
                    onClick = { viewModel.onAction(MirrorPickerAction.OnSelectMirror(mirror)) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                CustomMirrorRow(
                    selected = state.preference is MirrorPreference.Custom,
                    onClick = { viewModel.onAction(MirrorPickerAction.OnCustomMirrorClicked) },
                )
            }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            item {
                Button(
                    onClick = { viewModel.onAction(MirrorPickerAction.OnTestConnection) },
                    enabled = !state.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(Res.string.mirror_test_button))
                    }
                }
                state.testResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatTestResult(result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                DeployYourOwnHint(
                    onClick = { viewModel.onAction(MirrorPickerAction.OnDeployYourOwnClicked) },
                )
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (state.isCustomDialogVisible) {
        CustomMirrorDialog(
            draft = state.customDraft,
            error = state.customDraftError,
            onDraftChange = { viewModel.onAction(MirrorPickerAction.OnCustomDraftChanged(it)) },
            onConfirm = { viewModel.onAction(MirrorPickerAction.OnCustomMirrorConfirm) },
            onDismiss = { viewModel.onAction(MirrorPickerAction.OnCustomMirrorDismiss) },
        )
    }
}

@Composable
private fun CustomMirrorRow(
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.RadioButton,
                ).padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = stringResource(Res.string.mirror_custom_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun formatTestResult(result: TestResult): String =
    when (result) {
        is TestResult.Success -> stringResource(Res.string.mirror_test_success, result.latencyMs)
        is TestResult.HttpError -> stringResource(Res.string.mirror_test_http_error, result.code)
        TestResult.Timeout -> stringResource(Res.string.mirror_test_timeout)
        TestResult.DnsFailure -> stringResource(Res.string.mirror_test_dns_fail)
        is TestResult.Other -> stringResource(Res.string.mirror_test_other, result.message)
    }

private fun isMirrorSelected(
    mirror: MirrorConfig,
    pref: MirrorPreference,
): Boolean =
    when (pref) {
        MirrorPreference.Direct -> mirror.id == "direct"
        is MirrorPreference.Selected -> mirror.id == pref.id
        is MirrorPreference.Custom -> false
    }
```

The imports referenced by `CustomMirrorRow` (`Row`, `selectable`, `Alignment`, `Role`, `RadioButton`) are already in the file's import block above.

- [ ] **Step 2: Add the route to `GithubStoreGraph.kt`**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/navigation/GithubStoreGraph.kt`. Add:

```kotlin
@Serializable
data object MirrorPickerScreen : GithubStoreGraph
```

(Match whatever the file's convention is for `data object` routes — `SponsorScreen` is a good reference.)

- [ ] **Step 3: Wire the route in `AppNavigation.kt`**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt`. Locate the `composable<GithubStoreGraph.SponsorScreen>` block (or any data-object route) and add a sibling:

```kotlin
composable<GithubStoreGraph.MirrorPickerScreen> {
    MirrorPickerRoot(
        onNavigateBack = { navController.popBackStack() },
    )
}
```

Add the import `import zed.rainxch.tweaks.presentation.mirror.MirrorPickerRoot`.

If Task 14 left a commented-out navigation reference to `MirrorPickerScreen`, uncomment it now.

- [ ] **Step 4: Register `MirrorPickerViewModel` + `testHttpClient`**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`. Add the import:

```kotlin
import zed.rainxch.tweaks.presentation.mirror.MirrorPickerViewModel
```

Add to the module block:

```kotlin
        viewModelOf(::MirrorPickerViewModel)
```

Open `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`. Add a named test-client to the module that doesn't have `MirrorRewriteInterceptor`:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.qualifier.named
```

```kotlin
        single<HttpClient>(qualifier = named("test")) {
            createPlatformHttpClient(zed.rainxch.core.domain.model.ProxyConfig.System).config {
                install(HttpTimeout) {
                    requestTimeoutMillis = 5_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 5_000
                }
            }
        }
```

Update `MirrorPickerViewModel` Koin registration to inject the named client. Since `viewModelOf(::MirrorPickerViewModel)` doesn't accept named params, switch to the explicit form:

```kotlin
        viewModel {
            MirrorPickerViewModel(
                mirrorRepository = get(),
                testHttpClient = get(qualifier = org.koin.core.qualifier.named("test")),
            )
        }
```

- [ ] **Step 5: Verify the full build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/mirror/MirrorPickerRoot.kt \
        composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/navigation/GithubStoreGraph.kt \
        composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt \
        composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt \
        core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt
git commit -m "Add MirrorPickerRoot, route, and Koin wiring for the picker and its test client"
```

---

## Task 19 — Tweaks "Download Mirror" entry tile + manual smoke test

**Files:**
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/Network.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksAction.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksRoot.kt`

The Tweaks entry tile sits at the top of the existing Network section.

- [ ] **Step 1: Add the action**

Open `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksAction.kt`. Inside the sealed interface body add:

```kotlin
    data object OnMirrorPickerClick : TweaksAction
```

- [ ] **Step 2: Forward navigation in `TweaksRoot.kt`**

`TweaksRoot` doesn't currently navigate anywhere — it's a leaf screen. The navigation must propagate UP via the `onNavigateToMirrorPicker: () -> Unit` parameter pattern that other screens use (see `ProfileRoot.kt` for the pattern with `onNavigateToSponsor`).

Open `TweaksRoot.kt`. Add an `onNavigateToMirrorPicker: () -> Unit` parameter to the public `TweaksRoot` composable. In the `onAction` lambda passed down to `TweaksScreen`, add a branch that intercepts `TweaksAction.OnMirrorPickerClick` BEFORE forwarding to the ViewModel:

```kotlin
TweaksScreen(
    state = state,
    onAction = { action ->
        when (action) {
            TweaksAction.OnMirrorPickerClick -> onNavigateToMirrorPicker()
            else -> viewModel.onAction(action)
        }
    },
    snackbarState = snackbarState,
)
```

- [ ] **Step 3: Wire the entry into `Network.kt`**

Open `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/Network.kt`. The file currently exposes `LazyListScope.network(state, onAction)` (verify with `head -100`). The file already imports `OutlinedTextField`, `Row`, `Column`, `Modifier`, `Arrangement`, `MaterialTheme`, `Alignment`, `padding`, `fillMaxWidth`, `dp`, `Spacer`, `height`, `Icons.Filled.NetworkCheck`, `Icons.AutoMirrored.Filled.KeyboardArrowRight` (verify by reading the import block).

Add (or confirm imports for): `OutlinedCard`, `CardDefaults`, `RoundedCornerShape`, `Icon`, `Text`. Then add the `Res.string.mirror_tweaks_entry_label` import alongside the existing `import zed.rainxch.githubstore.core.presentation.res.*` (or as an explicit single-key import if the file uses explicit imports).

At the top of the `network` function — before the proxy form — add an entry tile:

```kotlin
item {
    OutlinedCard(
        onClick = { onAction(TweaksAction.OnMirrorPickerClick) },
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.NetworkCheck,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.mirror_tweaks_entry_label),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}
item {
    Spacer(Modifier.height(16.dp))
}
```

Add `import zed.rainxch.tweaks.presentation.TweaksAction` if the file imports `TweaksAction` qualified (most sections in this directory do — verify by checking neighbouring files like `Translation.kt`).

- [ ] **Step 4: Wire the navigation lambda from `composeApp`**

Open `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt`. Locate where `TweaksRoot` is called (search for `TweaksRoot(`). Add the `onNavigateToMirrorPicker` lambda:

```kotlin
TweaksRoot(
    // ... existing args ...
    onNavigateToMirrorPicker = {
        navController.navigate(GithubStoreGraph.MirrorPickerScreen)
    },
)
```

- [ ] **Step 5: Compile and assemble debug**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke-test the full flow on a debug build**

Sideload the assembled debug APK on an Android emulator or device:

1. Open the app → bottom nav → Tweaks → scroll to Network.
2. Tap "Download Mirror" → the picker screen opens.
3. The catalog renders 6 entries (from bundled fallback first, then live from backend within ~1s).
4. Tap a community mirror — it becomes selected (radio fills, persists across app restart).
5. Tap "Custom mirror…" → dialog opens. Enter `bad-template` → "Template must start with https://" appears. Enter `https://example.com` → "Template must contain {url} exactly once". Enter `https://example.com/{url}` → Save enables. Save → preference flips to Custom.
6. Tap "Test selected" → spinner → result text appears (success/error).
7. Tap the "deploy your own" footer → external browser opens at `https://github.com/hunshcn/gh-proxy`.
8. Pop back to Tweaks → kick a release-asset download from any repo → confirm it succeeds.

If anything in steps 1–8 fails, fix in place and re-run before committing.

- [ ] **Step 7: Commit**

```bash
git add feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/Network.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksAction.kt \
        feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksRoot.kt \
        composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/AppNavigation.kt
git commit -m "Add Download Mirror entry to Tweaks Network section linking to the picker"
```

---

## Plan complete

When all 19 tasks pass, the feature is shipped end-to-end: discoverable from Tweaks → Network → Download Mirror, full picker with bundled fallback + live status + latency, custom-mirror entry with validation, test connection, multi-source race + SHA-256 verification on every release asset, slow-download auto-suggest, and the full URL-rewriting plumbing for `github.com` / `api.github.com` / `raw.githubusercontent.com` / `objects.githubusercontent.com` requests on the GitHub-bound `HttpClient` only.

The spec calls for the multi-source race to apply to release-asset downloads exclusively (API calls go mirror-only) — the orchestrator routes only `asset.downloadUrl` through `MultiSourceDownloader`. API calls keep using the GitHub `HttpClient` directly, where the `MirrorRewriteInterceptor` rewrites them transparently. The privacy-relevant `digest` field is fetched directly from `api.github.com` (via the GitHub client, which goes through whatever mirror is selected — a known limitation acknowledged in the spec's threat model).

Both `HttpRedirect { checkHttpMethod = false }` and the `NO_MIRROR_REWRITE` attribute key are wired so the rare mirror that surfaces an `objects.githubusercontent.com` redirect to the client gets rewritten on the next hop without breaking the direct-branch race.
