package zed.rainxch.core.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class BackendSearchResponse(
    val items: List<BackendRepoResponse>,
    val totalHits: Int,
    val processingTimeMs: Int,
    val source: String? = null,
)
