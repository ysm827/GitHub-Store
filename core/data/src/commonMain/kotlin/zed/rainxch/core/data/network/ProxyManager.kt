package zed.rainxch.core.data.network

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    private val mirrorTemplate = AtomicReference<String?>(null)
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
     * when Direct. Read by [zed.rainxch.core.data.network.MirrorRewriteInterceptor]
     * on every outbound GitHub request — must be hot-path safe (atomic, no I/O).
     */
    fun currentMirrorTemplate(): String? = mirrorTemplate.get()

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
                combine(
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
                    mirrorTemplate.set(template)
                }
            }
    }
}
