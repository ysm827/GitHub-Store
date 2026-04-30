package zed.rainxch.core.domain.system

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.DownloadProgress

interface MultiSourceDownloader {
    fun download(
        githubUrl: String,
        suggestedFileName: String? = null,
    ): Flow<DownloadProgress>
}
