package zed.rainxch.details.domain.util

import zed.rainxch.core.domain.model.GithubRelease
import zed.rainxch.core.domain.util.VersionMath

/**
 * Thin wrapper that defers all comparisons to
 * [zed.rainxch.core.domain.util.VersionMath]. Kept as a separate type
 * because the details feature also needs a release-list-aware
 * downgrade check (see [isDowngradeVersion]) — that fallback is
 * specific to the install flow on the details screen, not something
 * the rest of the app needs.
 */
object VersionHelper {
    fun normalizeVersion(version: String?): String = VersionMath.normalizeVersion(version)

    /**
     * Returns `true` when installing [candidate] over [current] would be
     * a downgrade.
     *
     * Strategy:
     *  1. Trust [VersionMath.compareVersions] when both inputs have a
     *     recognisable versioning scheme (SemVer / CalVer). The sign of
     *     the comparator is authoritative — list-position is too
     *     unreliable since GitHub's release ordering follows
     *     `published_at`, and maintainers can reorder by republishing
     *     or backdating a release.
     *  2. Fall back to list-index ordering only when at least one input
     *     has no parseable scheme (commit-hash tags, ad-hoc strings).
     *     The release feed is newest-first, so a candidate that appears
     *     later in the list is older.
     *  3. As a last resort, when neither lookup nor scheme detection
     *     yields an answer, fall through to [VersionMath.compareVersions]
     *     (which itself falls back to lexicographic comparison).
     *
     * Cross-references for the install flow caller behaviour:
     *  - `DetailsViewModel.install()` skips this check entirely when
     *    `normalizeVersion(candidate) == normalizeVersion(current)`.
     *  - The result gates [DowngradeWarning] so a `false` here proceeds
     *    straight to install.
     */
    fun isDowngradeVersion(
        candidate: String,
        current: String,
        allReleases: List<GithubRelease>,
    ): Boolean {
        val candidateScheme = VersionMath.detectScheme(candidate)
        val currentScheme = VersionMath.detectScheme(current)
        val bothSchemed =
            candidateScheme != VersionMath.Scheme.Unknown &&
                currentScheme != VersionMath.Scheme.Unknown

        val cmp = VersionMath.compareVersions(candidate, current)

        if (bothSchemed) {
            return cmp < 0
        }

        if (cmp == 0) return false

        val normalizedCandidate = VersionMath.normalizeVersion(candidate)
        val normalizedCurrent = VersionMath.normalizeVersion(current)
        val candidateIndex =
            allReleases.indexOfFirst {
                VersionMath.normalizeVersion(it.tagName) == normalizedCandidate
            }
        val currentIndex =
            allReleases.indexOfFirst {
                VersionMath.normalizeVersion(it.tagName) == normalizedCurrent
            }
        if (candidateIndex != -1 && currentIndex != -1) {
            return candidateIndex > currentIndex
        }
        return cmp < 0
    }

    /**
     * Three-way comparison delegating to [VersionMath.compareVersions].
     * Kept on this surface so existing call sites don't have to learn
     * the new helper's name.
     */
    fun compareSemanticVersions(
        a: String,
        b: String,
    ): Int = VersionMath.compareVersions(a, b)
}
