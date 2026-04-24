package zed.rainxch.core.domain.system

/**
 * Surfaces live download progress in the system notification shade so
 * users can track downloads while the app is in the background (see
 * GitHub-Store#373).
 *
 * Lifecycle: [DownloadOrchestrator] is the single source of truth for
 * download state. A platform observer subscribes to
 * [DownloadOrchestrator.downloads] and calls [notifyProgress] on every
 * `Queued` / `Downloading` emission, and [clearProgress] on any terminal
 * or install-stage transition. The orchestrator itself does not know
 * about this notifier — wiring is one-way.
 *
 * Platform contracts:
 *  - **Android**: persistent ongoing notification with progress bar and
 *    a "Cancel" action that broadcasts back to
 *    `DownloadCancelReceiver`. Channel id `app_downloads`.
 *  - **JVM/Desktop**: no-op. Desktop downloads happen with the window
 *    visible and installers complete synchronously via the OS dialog;
 *    there is no equivalent of the Android notification shade to target.
 */
interface DownloadProgressNotifier {
    /**
     * Posts or updates the progress notification for [packageName].
     * Safe to call on every progress tick — the Android impl uses
     * `setOnlyAlertOnce(true)` so the notification updates silently.
     *
     * @param packageName Stable key; also drives the notification id.
     * @param appName User-visible title.
     * @param versionTag Release tag shown alongside byte counts.
     * @param percent 0..100, or `null` when the server did not send
     *   a `Content-Length` header (indeterminate spinner).
     * @param bytesDownloaded Bytes received so far — shown in the
     *   notification's content text.
     * @param totalBytes Expected total, or `null` if unknown.
     */
    fun notifyProgress(
        packageName: String,
        appName: String,
        versionTag: String,
        percent: Int?,
        bytesDownloaded: Long,
        totalBytes: Long?,
    )

    /**
     * Dismisses the progress notification for [packageName]. Called on
     * any transition out of `Queued` / `Downloading` — including
     * `AwaitingInstall`, which is owned by [PendingInstallNotifier].
     */
    fun clearProgress(packageName: String)
}
