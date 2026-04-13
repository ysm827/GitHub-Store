package zed.rainxch.core.data.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import zed.rainxch.core.domain.system.PendingInstallNotifier

/**
 * Android implementation of [PendingInstallNotifier].
 *
 * # Behaviour
 *
 * - Each pending install gets its own notification, keyed by the
 *   package name's hash code (so two pending installs of different
 *   apps don't replace each other in the shade).
 * - Tapping the notification (or the explicit "Open" action) launches
 *   `MainActivity` via the `githubstore://apps` deep link, which the
 *   parser routes to the apps tab where the user finishes the install.
 * - Channel `app_updates` (already declared in `GithubStoreApp`) is
 *   reused — pending installs are conceptually a flavour of update
 *   notification and don't deserve a separate channel.
 *
 * # Permission gating
 *
 * On Android 13+, posting requires `POST_NOTIFICATIONS`. We check it
 * silently rather than throwing — if the user denied the permission,
 * the install still completes, they just don't get notified. The apps
 * row still shows the pending state, so this is a graceful degrade.
 */
class AndroidPendingInstallNotifier(
    private val context: Context,
) : PendingInstallNotifier {
    @SuppressLint("MissingPermission")
    override fun notifyPending(
        packageName: String,
        repoOwner: String,
        repoName: String,
        appName: String,
        versionTag: String,
    ) {
        if (!hasNotificationPermission()) return

        // Deep link straight to the *Details* page for this specific
        // app. The existing `githubstore://repo/owner/name` route is
        // already wired in `DeepLinkParser` and lands on Details with
        // the right repository pre-loaded — the user can tap install
        // immediately. The Details page also detects the parked file
        // via `pendingInstallFilePath` and skips re-downloading.
        val safeOwner = sanitizeForUri(repoOwner)
        val safeRepo = sanitizeForUri(repoName)
        val uri =
            if (safeOwner.isNotEmpty() && safeRepo.isNotEmpty()) {
                "githubstore://repo/$safeOwner/$safeRepo"
            } else {
                // Synthetic-key fallback (e.g. for fresh installs
                // where we don't know the package's owner/repo
                // yet) — open the apps tab as before.
                FALLBACK_URI
            }
        val deepLinkIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                deepLinkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, UPDATES_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(appName)
                .setContentText(versionTag)
                .setSubText(SUBTEXT)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat
            .from(context)
            .notify(notificationIdFor(packageName), notification)
    }

    override fun clearPending(packageName: String) {
        NotificationManagerCompat
            .from(context)
            .cancel(notificationIdFor(packageName))
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Stable per-package notification id. We hash the package name and
     * mask off the high bits so it lands in a positive int range that
     * doesn't collide with the existing notification ids used by the
     * update workers (1004, 1005). Hash collisions are vanishingly
     * rare in practice for package names; if they happen the worst
     * case is that two pending installs share a notification, which
     * is the same outcome as having only one notifier.
     */
    private fun notificationIdFor(packageName: String): Int =
        NOTIFICATION_ID_BASE + (packageName.hashCode() and 0x00FFFFFF)

    /**
     * Defensive sanitisation for the deep-link path components.
     * `DeepLinkParser.isStrictlyValidOwnerRepo` already rejects
     * weird input, so this just strips characters that would break
     * URI parsing before they reach the parser.
     */
    private fun sanitizeForUri(input: String): String {
        if (input.isBlank()) return ""
        return input.filter { ch ->
            ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_'
        }
    }

    private companion object {
        const val UPDATES_CHANNEL_ID = "app_updates"
        const val FALLBACK_URI = "githubstore://apps"
        const val SUBTEXT = "Ready to install"

        // Existing worker notifications use 1001-1005; start the
        // pending-install id space comfortably above that.
        const val NOTIFICATION_ID_BASE = 2000
    }
}
