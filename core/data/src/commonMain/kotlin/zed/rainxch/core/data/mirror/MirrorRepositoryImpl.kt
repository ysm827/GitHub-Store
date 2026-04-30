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
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
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
                val previousCatalog = _catalog.value
                _catalog.value = configs
                preferences.edit { prefs ->
                    prefs[MirrorPersistence.CACHED_MIRROR_LIST_JSON_KEY] = json.encodeToString(MirrorListResponse.serializer(), response)
                    prefs[MirrorPersistence.CACHED_MIRROR_LIST_AT_KEY] = Clock.System.now().toEpochMilliseconds()
                }
                checkSelectedMirrorStillExists(fresh = configs, previous = previousCatalog)
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

    override suspend fun snoozeAutoSuggest(forMs: Long) {
        preferences.edit { prefs ->
            prefs[MirrorPersistence.AUTO_SUGGEST_SNOOZE_UNTIL_KEY] =
                kotlin.time.Clock.System.now().toEpochMilliseconds() + forMs
        }
    }

    override suspend fun dismissAutoSuggestPermanently() {
        preferences.edit { prefs ->
            prefs[MirrorPersistence.AUTO_SUGGEST_DISMISSED_KEY] = true
        }
    }

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

    private suspend fun checkSelectedMirrorStillExists(
        fresh: List<MirrorConfig>,
        previous: List<MirrorConfig>,
    ) {
        val pref = observePreference().first()
        if (pref !is MirrorPreference.Selected) return
        val match = fresh.firstOrNull { it.id == pref.id }
        if (match == null) {
            val previousName = previous.firstOrNull { it.id == pref.id }?.name ?: pref.id
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
