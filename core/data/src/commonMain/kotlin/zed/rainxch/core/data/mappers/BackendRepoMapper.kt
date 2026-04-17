package zed.rainxch.core.data.mappers

import zed.rainxch.core.data.dto.BackendRepoResponse
import zed.rainxch.core.domain.model.DiscoveryPlatform
import zed.rainxch.core.domain.model.GithubRepoSummary
import zed.rainxch.core.domain.model.GithubUser

fun BackendRepoResponse.toSummary(): GithubRepoSummary =
    GithubRepoSummary(
        id = id,
        name = name,
        fullName = fullName,
        owner = GithubUser(
            id = 0,
            login = owner.login,
            avatarUrl = owner.avatarUrl ?: "",
            htmlUrl = "https://github.com/${owner.login}",
        ),
        description = description,
        defaultBranch = defaultBranch ?: "main",
        htmlUrl = htmlUrl,
        stargazersCount = stargazersCount,
        forksCount = forksCount,
        language = language,
        topics = topics.ifEmpty { null },
        releasesUrl = releasesUrl ?: "https://api.github.com/repos/$fullName/releases{/id}",
        updatedAt = latestReleaseDate ?: updatedAt ?: "",
        availablePlatforms = buildAvailablePlatforms(),
    )

private fun BackendRepoResponse.buildAvailablePlatforms(): List<DiscoveryPlatform> =
    buildList {
        if (hasInstallersAndroid) add(DiscoveryPlatform.Android)
        if (hasInstallersWindows) add(DiscoveryPlatform.Windows)
        if (hasInstallersMacos) add(DiscoveryPlatform.Macos)
        if (hasInstallersLinux) add(DiscoveryPlatform.Linux)
    }
