package zed.rainxch.core.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `pendingInstallFilePath` to `installed_apps`.
 *
 * Set by `DefaultDownloadOrchestrator` when an
 * `InstallPolicy.InstallWhileForeground` download finishes after the
 * foreground screen has been destroyed. The apps list shows a
 * "Ready to install" row with a one-tap install action when this is
 * non-null. Cleared on successful install.
 *
 * Nullable, no default — existing rows have `null` and behave the
 * same as before.
 */
val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN pendingInstallFilePath TEXT")
        }
    }
