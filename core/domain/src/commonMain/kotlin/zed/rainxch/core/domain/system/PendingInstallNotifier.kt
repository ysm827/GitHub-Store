package zed.rainxch.core.domain.system

/**
 * Surfaces "your download is ready, tap to install" notifications.
 *
 * Used by [DownloadOrchestrator] when an [InstallPolicy.InstallWhileForeground]
 * download finishes after the foreground screen has been destroyed
 * (the user navigated away mid-download). The notification gives them
 * a one-tap path back into the app to complete the install.
 *
 * Platform contracts:
 *  - **Android**: real notification with a deep link into the Details
 *    page for the specific app (`githubstore://repo/owner/name`).
 *    Falls back to the apps tab (`githubstore://apps`) when owner/repo
 *    are unavailable. Channel id `app_updates`.
 *  - **JVM/Desktop**: no-op (Desktop doesn't background-install APKs;
 *    the apps tab is hidden on Desktop anyway).
 */
interface PendingInstallNotifier {
    /**
     * Posts (or updates) the notification for [packageName].
     *
     * @param packageName Used as a stable notification id so multiple
     *   pending installs each get their own row instead of replacing
     *   each other.
     * @param repoOwner GitHub owner — used to build the
     *   `githubstore://repo/owner/name` deep link so tapping the
     *   notification opens the Details page for *this specific app*.
     * @param repoName GitHub repo — paired with [repoOwner] for the
     *   deep link.
     * @param appName User-visible app name shown as the notification
     *   title.
     * @param versionTag Release tag shown as the notification body
     *   (e.g. "v1.2.3").
     */
    fun notifyPending(
        packageName: String,
        repoOwner: String,
        repoName: String,
        appName: String,
        versionTag: String,
    )

    /**
     * Dismisses the pending-install notification for [packageName].
     * Called by the orchestrator when the user installs the file
     * (either from the apps row or from the notification action).
     */
    fun clearPending(packageName: String)
}
