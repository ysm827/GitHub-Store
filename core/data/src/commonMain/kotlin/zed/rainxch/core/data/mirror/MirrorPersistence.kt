package zed.rainxch.core.data.mirror

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object MirrorPersistence {
    val PREFERRED_MIRROR_KEY = stringPreferencesKey("mirror_preferred_id")
    val CUSTOM_MIRROR_TEMPLATE_KEY = stringPreferencesKey("mirror_custom_template")
    val CACHED_MIRROR_LIST_JSON_KEY = stringPreferencesKey("mirror_cached_list_json")
    val CACHED_MIRROR_LIST_AT_KEY = longPreferencesKey("mirror_cached_list_at")
    val AUTO_SUGGEST_SNOOZE_UNTIL_KEY = longPreferencesKey("mirror_auto_suggest_snooze_until")
    val AUTO_SUGGEST_DISMISSED_KEY = booleanPreferencesKey("mirror_auto_suggest_dismissed")

    /** Sentinel value stored when the user picks the Custom mirror entry. */
    const val CUSTOM_MIRROR_ID_SENTINEL = "__custom__"

    /** Default sentinel — Direct GitHub. */
    const val DIRECT_MIRROR_ID = "direct"
}
