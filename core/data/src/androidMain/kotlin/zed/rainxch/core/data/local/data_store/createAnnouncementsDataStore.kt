package zed.rainxch.core.data.local.data_store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

fun createAnnouncementsDataStore(context: Context): DataStore<Preferences> =
    createDataStore(
        producePath = {
            context.filesDir.resolve(announcementsDataStoreFileName).absolutePath
        },
    )
