package zed.rainxch.core.data.services

import android.content.Context
import android.os.Environment
import java.io.File

class AndroidFileLocationsProvider(
    private val context: Context,
) : zed.rainxch.core.data.services.FileLocationsProvider {
    override fun appDownloadsDir(): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IllegalStateException("Failed to create downloads directory: ${dir.absolutePath}")
        }
        return dir.absolutePath
    }

    override fun userDownloadsDir(): String {
        return appDownloadsDir()
    }

    override fun setExecutableIfNeeded(path: String) {
        // No-op on Android
    }

    override fun getCacheSizeBytes(): Long {
        val dir = File(appDownloadsDir())
        return calculateDirSize(dir)
    }

    override fun clearCacheFiles(): Boolean {
        val dir = File(appDownloadsDir())
        return deleteDirectoryContents(dir)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    private fun deleteDirectoryContents(dir: File): Boolean {
        if (!dir.exists()) return true
        var allDeleted = true
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (!deleteDirectoryContents(file)) allDeleted = false
                if (!file.delete()) allDeleted = false
            } else {
                if (!file.delete()) allDeleted = false
            }
        }
        return allDeleted
    }
}
