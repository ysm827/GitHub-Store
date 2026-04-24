package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.DownloadProgressNotifier

/**
 * Desktop has no system-shade equivalent that matches the Android
 * download-notification UX, so this stays a no-op. The orchestrator
 * still calls in unconditionally, avoiding a platform branch.
 */
class DesktopDownloadProgressNotifier : DownloadProgressNotifier {
    override fun notifyProgress(
        packageName: String,
        appName: String,
        versionTag: String,
        percent: Int?,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ) = Unit

    override fun clearProgress(packageName: String) = Unit
}
