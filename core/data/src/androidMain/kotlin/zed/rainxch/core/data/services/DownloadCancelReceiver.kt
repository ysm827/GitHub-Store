package zed.rainxch.core.data.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import zed.rainxch.core.domain.system.DownloadOrchestrator

/**
 * Receives the "Cancel" action fired by the download progress
 * notification (see [AndroidDownloadProgressNotifier]) and routes it to
 * [DownloadOrchestrator.cancel].
 *
 * Declared in the manifest so it fires even when the app is in the
 * background and the process has been trimmed — static registration is
 * what Android guarantees survival for post-notification callbacks.
 *
 * Koin's [GlobalContext] is used to resolve the orchestrator and the
 * application-scoped [CoroutineScope] because a manifest-registered
 * receiver has no injection point; the orchestrator is already a
 * singleton so `GlobalContext.get()` returns the same instance the rest
 * of the app uses.
 */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isBlank()) {
            Logger.w { "DownloadCancelReceiver: missing package name extra" }
            return
        }

        // goAsync keeps the BroadcastReceiver alive long enough for the
        // suspend call to complete. Without it, the receiver returns as
        // soon as onReceive exits and the coroutine may be killed.
        val pending = goAsync()
        val koin = GlobalContext.getOrNull()
        if (koin == null) {
            Logger.w { "DownloadCancelReceiver: Koin not initialized, ignoring cancel for $packageName" }
            pending.finish()
            return
        }

        val orchestrator = koin.get<DownloadOrchestrator>()
        val scope = koin.get<CoroutineScope>()
        scope.launch {
            try {
                orchestrator.cancel(packageName)
            } catch (t: Throwable) {
                Logger.e(t) { "DownloadCancelReceiver: cancel failed for $packageName" }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "zed.rainxch.githubstore.action.CANCEL_DOWNLOAD"
        const val EXTRA_PACKAGE_NAME = "zed.rainxch.githubstore.extra.PACKAGE_NAME"
    }
}
