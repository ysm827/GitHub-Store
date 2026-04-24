package zed.rainxch.core.domain.model

import zed.rainxch.core.domain.util.VersionMath

/**
 * Single source of truth for "should this release be treated as a
 * pre-release across the app".
 *
 * Combines:
 *  - [GithubRelease.isPrerelease] — the authoritative GitHub API flag.
 *  - [VersionMath.isPreReleaseTag] on [GithubRelease.tagName] — catches
 *    the common case where a maintainer publishes `v2.0.0-rc.1` but
 *    forgets to tick the "This is a pre-release" box. Without the
 *    tag heuristic, an opted-out user would be silently offered that
 *    build as if it were stable.
 *  - [VersionMath.isPreReleaseTag] on [GithubRelease.name] — some
 *    maintainers only put the `beta` marker in the human-readable
 *    release title (e.g. tag=`2.0.0`, name=`2.0.0 (beta)`).
 *
 * Every UI that shows a "Pre-release" badge and every filter that
 * decides whether to surface a release to a given user MUST use this
 * helper, otherwise the flag-vs-tag mismatch surfaces as a silent
 * bug.
 */
fun GithubRelease.isEffectivelyPreRelease(): Boolean =
    isPrerelease ||
        VersionMath.isPreReleaseTag(tagName) ||
        VersionMath.isPreReleaseTag(name)

/**
 * Specific label for this release's pre-release marker — `"Beta"`,
 * `"Alpha"`, `"RC"`, etc. — or `null` if no marker was detected.
 *
 * Tries the tag first (where the marker most often lives), falls
 * back to the release name (some maintainers put the marker only
 * in the title). Returns `null` when neither contains a recognised
 * marker, in which case callers that still want to show a badge
 * should check [isEffectivelyPreRelease] and fall back to a generic
 * "Pre-release" pill — an opted-in API flag with no visible marker
 * is still a pre-release, just one without a specific channel name.
 */
fun GithubRelease.preReleaseLabel(): String? =
    VersionMath.preReleaseMarkerLabel(tagName)
        ?: VersionMath.preReleaseMarkerLabel(name)
