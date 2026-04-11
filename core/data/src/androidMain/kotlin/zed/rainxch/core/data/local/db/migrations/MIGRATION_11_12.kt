package zed.rainxch.core.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the multi-layer variant fingerprint columns to `installed_apps`:
 *
 *  - `preferredAssetTokens`: serialized token-set fingerprint (closed
 *    vocabulary of arch / flavor tokens, sorted, joined with `|`)
 *  - `assetGlobPattern`: glob-pattern fingerprint with version-shaped
 *    substrings replaced by `*`
 *  - `pickedAssetIndex`: zero-based index of the picked asset in the
 *    release's installable-asset list (same-position fallback)
 *  - `pickedAssetSiblingCount`: total installable assets in the picked
 *    release, pairs with `pickedAssetIndex`
 *
 * All four columns are nullable so existing rows keep their current
 * single-layer behaviour: the resolver falls back through the layers
 * in order, and an old row with only `preferredAssetVariant` set still
 * works via the legacy substring-tail match.
 */
val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN preferredAssetTokens TEXT")
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN assetGlobPattern TEXT")
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN pickedAssetIndex INTEGER")
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN pickedAssetSiblingCount INTEGER")
        }
    }
