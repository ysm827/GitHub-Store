package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.PendingInstallNotifier

/**
 * Desktop has no pending-install flow:
 *  - The apps tab is hidden on Desktop (set via `Platform.ANDROID` gate
 *    in the bottom nav), so the user has no place to "tap to install"
 *  - Desktop installs always run in-process and complete synchronously
 *    via the OS package manager dialog
 *
 * Notifier is a no-op so the orchestrator can still call into it
 * unconditionally without platform branching.
 */
class DesktopPendingInstallNotifier : PendingInstallNotifier {
    override fun notifyPending(
        packageName: String,
        repoOwner: String,
        repoName: String,
        appName: String,
        versionTag: String,
    ) = Unit

    override fun clearPending(packageName: String) = Unit
}
