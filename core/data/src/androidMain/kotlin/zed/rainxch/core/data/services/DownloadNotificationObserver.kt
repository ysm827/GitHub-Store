package zed.rainxch.core.data.services

import android.os.SystemClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import zed.rainxch.core.domain.system.DownloadOrchestrator
import zed.rainxch.core.domain.system.DownloadProgressNotifier
import zed.rainxch.core.domain.system.DownloadStage
import zed.rainxch.core.domain.system.OrchestratedDownload

/**
 * Single long-lived subscriber that translates
 * [DownloadOrchestrator.downloads] state transitions into calls on
 * [DownloadProgressNotifier].
 *
 * # Why it's its own class
 *
 * The orchestrator stays platform-agnostic (common code, no Android
 * imports) and doesn't know about notifications. The observer lives on
 * `androidMain` alongside the Android notifier and is only started from
 * [zed.rainxch.githubstore.app.GithubStoreApp], which means the whole
 * feature is Android-only without any platform branches in shared code.
 *
 * # Transition rules
 *
 *  - `Queued`, `Downloading` → post / update progress notification.
 *  - Anything else (`Installing`, `AwaitingInstall`, `Completed`,
 *    `Cancelled`, `Failed`) or entry removal → clear. `AwaitingInstall`
 *    is owned by [zed.rainxch.core.domain.system.PendingInstallNotifier]
 *    which posts its own "ready to install" row.
 *
 * # Throttling
 *
 * The orchestrator emits on every ~8KB chunk (hundreds of emissions
 * per second on a fast link). Android silently drops notification
 * updates posted faster than ~200ms for the same id, and every
 * `NotificationManagerCompat.notify` is a Binder round-trip, so
 * letting every emission through both wastes CPU and produces a stuck
 * progress bar that jumps at the end.
 *
 * We coalesce in-stage ticks to at most one post per
 * [PROGRESS_UPDATE_INTERVAL_MS] per package, but always flush
 * immediately on stage transitions (`Queued → Downloading`,
 * `Downloading → Completed`, etc.) and on 100%-percent emissions so
 * the final frame is never skipped.
 *
 * # Lifecycle
 *
 * Started once from the Application's `onCreate` via [start], collected
 * on the app-scoped coroutine scope (same one Koin provides). No
 * explicit stop — the process going away ends the flow.
 */
class DownloadNotificationObserver(
    private val orchestrator: DownloadOrchestrator,
    private val notifier: DownloadProgressNotifier,
) {
    @Volatile
    private var job: Job? = null

    private val lastStages = mutableMapOf<String, DownloadStage>()
    private val lastNotifiedAt = mutableMapOf<String, Long>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                try {
                    orchestrator.downloads.collect { snapshot ->
                        try {
                            reconcile(snapshot)
                        } catch (t: Throwable) {
                            // Never let a NotificationManager hiccup
                            // collapse the whole flow — progress
                            // notifications are best-effort.
                            Logger.w(t) { "DownloadNotificationObserver: reconcile failed, continuing" }
                        }
                    }
                } finally {
                    // Reset so a subsequent start() on this process
                    // (e.g. after the caller's scope restarts) can
                    // resubscribe instead of silently no-op'ing on the
                    // `job?.isActive == true` guard.
                    job = null
                }
            }
    }

    private fun reconcile(snapshot: Map<String, OrchestratedDownload>) {
        // Clear notifications for entries that vanished from the map
        // (e.g. dismissed after Completed, or cleared on Cancelled).
        val removed = lastStages.keys - snapshot.keys
        for (pkg in removed) {
            clearProgressSafely(pkg)
            lastStages.remove(pkg)
            lastNotifiedAt.remove(pkg)
        }

        for ((pkg, entry) in snapshot) {
            val previous = lastStages[pkg]
            val stageChanged = previous != entry.stage
            when (entry.stage) {
                DownloadStage.Queued, DownloadStage.Downloading -> {
                    val now = SystemClock.uptimeMillis()
                    val last = lastNotifiedAt[pkg] ?: 0L
                    val shouldPost =
                        stageChanged ||
                            entry.progressPercent == 100 ||
                            (now - last) >= PROGRESS_UPDATE_INTERVAL_MS
                    if (shouldPost) {
                        try {
                            notifier.notifyProgress(
                                packageName = pkg,
                                appName = entry.displayAppName,
                                versionTag = entry.releaseTag.ifBlank { entry.assetName },
                                percent = entry.progressPercent,
                                bytesDownloaded = entry.bytesDownloaded,
                                totalBytes = entry.totalBytes,
                            )
                            lastNotifiedAt[pkg] = now
                        } catch (t: Throwable) {
                            Logger.w(t) { "DownloadNotificationObserver: notifyProgress failed for $pkg" }
                        }
                    }
                }

                DownloadStage.Installing,
                DownloadStage.AwaitingInstall,
                DownloadStage.Completed,
                DownloadStage.Cancelled,
                DownloadStage.Failed,
                -> {
                    if (previous == DownloadStage.Queued || previous == DownloadStage.Downloading) {
                        clearProgressSafely(pkg)
                        lastNotifiedAt.remove(pkg)
                    }
                }
            }
            lastStages[pkg] = entry.stage
        }
    }

    private fun clearProgressSafely(pkg: String) {
        try {
            notifier.clearProgress(pkg)
        } catch (t: Throwable) {
            Logger.w(t) { "DownloadNotificationObserver: clearProgress failed for $pkg" }
        }
    }

    private companion object {
        // Comfortably above Android's ~200ms internal drop threshold
        // while still feeling live to the eye.
        const val PROGRESS_UPDATE_INTERVAL_MS = 400L
    }
}
