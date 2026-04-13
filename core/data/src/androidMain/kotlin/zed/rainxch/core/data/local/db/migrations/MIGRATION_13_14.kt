package zed.rainxch.core.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds version + asset name metadata for parked downloads to
 * `installed_apps`:
 *
 *  - `pendingInstallVersion`: release tag of the version represented
 *    by `pendingInstallFilePath`. Lets the Details screen detect
 *    "the parked file matches the currently-selected release" and
 *    skip re-downloading on install.
 *  - `pendingInstallAssetName`: original (unscoped) asset filename
 *    of the parked file. Pairs with `pendingInstallVersion` for the
 *    Details-screen "ready to install" match.
 *
 * Both nullable, no default — existing rows have `null` for both
 * and continue working unchanged (the apps list still shows
 * "Ready to install" based on `pendingInstallFilePath` alone; only
 * the Details-screen short-circuit needs the version match).
 */
val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN pendingInstallVersion TEXT")
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN pendingInstallAssetName TEXT")
        }
    }
