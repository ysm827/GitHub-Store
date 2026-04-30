package zed.rainxch.core.data.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
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

    private val mutex = Mutex()
    private val samples: ArrayDeque<Pair<Long, Long>> = ArrayDeque()
    private val recentSlowEvents: ArrayDeque<Long> = ArrayDeque()

    private val _suggestMirror =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val suggestMirror: Flow<Unit> = _suggestMirror.asSharedFlow()

    override fun onProgress(progress: DownloadProgress) {
        appScope.launch {
            mutex.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                samples.addLast(now to progress.bytesDownloaded)
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
        recentSlowEvents.addLast(timestampMs)
        while (recentSlowEvents.isNotEmpty() && recentSlowEvents.first() < timestampMs - windowMs) {
            recentSlowEvents.removeFirst()
        }
        if (recentSlowEvents.size < triggerCount) return

        if (ProxyManager.currentMirrorTemplate() != null) return
        val prefs = preferences.data.first()
        if (prefs[MirrorPersistence.AUTO_SUGGEST_DISMISSED_KEY] == true) return
        val snoozeUntil = prefs[MirrorPersistence.AUTO_SUGGEST_SNOOZE_UNTIL_KEY] ?: 0L
        if (snoozeUntil > timestampMs) return

        recentSlowEvents.clear()
        _suggestMirror.tryEmit(Unit)
    }
}
