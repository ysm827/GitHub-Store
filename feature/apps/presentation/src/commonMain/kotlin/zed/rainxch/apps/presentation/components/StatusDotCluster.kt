package zed.rainxch.apps.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.apps.presentation.model.AppItem
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.apps_compact_status_filter_active
import zed.rainxch.githubstore.core.presentation.res.apps_compact_status_pending_install
import zed.rainxch.githubstore.core.presentation.res.apps_compact_status_pre_release_on
import zed.rainxch.githubstore.core.presentation.res.apps_compact_status_ready_to_install
import zed.rainxch.githubstore.core.presentation.res.apps_compact_status_variant_pinned
import zed.rainxch.githubstore.core.presentation.res.apps_compact_status_variant_stale

/**
 * Per-app state flags that the compact row encodes visually. Each flag uses
 * BOTH a unique [DotShape] AND a tinted color so that the cluster passes
 * WCAG 1.4.1 (no information by color alone). Screen readers see the
 * cluster's merged contentDescription via the parent row's semantics.
 */
data class CompactStatusFlags(
    val filterActive: Boolean = false,
    val variantPinned: Boolean = false,
    val variantStale: Boolean = false,
    val preReleaseOn: Boolean = false,
    val pendingInstall: Boolean = false,
    val readyToInstall: Boolean = false,
)

/**
 * Computes the visible status flags for an [AppItem]. Memoised on its
 * inputs so that the row's recomposition doesn't re-allocate the data
 * class on every download-progress tick.
 */
@Composable
fun rememberCompactStatusFlags(appItem: AppItem): CompactStatusFlags {
    val app = appItem.installedApp
    return remember(
        app.assetFilterRegex,
        app.fallbackToOlderReleases,
        app.preferredAssetVariant,
        app.preferredVariantStale,
        app.includePreReleases,
        app.isPendingInstall,
        app.pendingInstallFilePath,
    ) {
        CompactStatusFlags(
            filterActive = !app.assetFilterRegex.isNullOrBlank() || app.fallbackToOlderReleases,
            variantPinned = !app.preferredAssetVariant.isNullOrBlank() && !app.preferredVariantStale,
            variantStale = app.preferredVariantStale,
            preReleaseOn = app.includePreReleases,
            pendingInstall = app.isPendingInstall && app.pendingInstallFilePath == null,
            readyToInstall = app.pendingInstallFilePath != null,
        )
    }
}

/**
 * Renders the active flags as a compact row of 8dp shapes. Each flag has a
 * dedicated shape (circle / square / triangle / diamond / ring / chevron) so
 * shape-only viewers (deuteranopia, monochrome themes, low contrast) still
 * tell the flags apart.
 */
@Composable
fun StatusDotCluster(
    flags: CompactStatusFlags,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    val items = buildList {
        if (flags.readyToInstall) add(StatusItem(DotShape.Ring, cs.primary))
        if (flags.pendingInstall) add(StatusItem(DotShape.Chevron, cs.tertiary))
        if (flags.variantStale) add(StatusItem(DotShape.Triangle, cs.error))
        if (flags.variantPinned) add(StatusItem(DotShape.Diamond, cs.primary))
        if (flags.filterActive) add(StatusItem(DotShape.Square, cs.primary))
        if (flags.preReleaseOn) add(StatusItem(DotShape.Circle, cs.tertiary))
    }

    if (items.isEmpty()) return

    Row(
        modifier = modifier.semantics { contentDescription = "" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Canvas(modifier = Modifier.size(8.dp)) {
                drawShape(item.shape, item.color)
            }
        }
    }
}

private data class StatusItem(val shape: DotShape, val color: Color)

private enum class DotShape {
    Circle,
    Square,
    Triangle,
    Diamond,
    Ring,
    Chevron,
}

private fun DrawScope.drawShape(shape: DotShape, color: Color) {
    val s = size.minDimension
    when (shape) {
        DotShape.Circle -> drawCircle(color = color, radius = s / 2f)
        DotShape.Square ->
            drawRect(color = color, size = androidx.compose.ui.geometry.Size(s, s))
        DotShape.Triangle -> {
            val path =
                Path().apply {
                    moveTo(s / 2f, 0f)
                    lineTo(s, s)
                    lineTo(0f, s)
                    close()
                }
            drawPath(path, color)
        }
        DotShape.Diamond -> {
            val path =
                Path().apply {
                    moveTo(s / 2f, 0f)
                    lineTo(s, s / 2f)
                    lineTo(s / 2f, s)
                    lineTo(0f, s / 2f)
                    close()
                }
            drawPath(path, color)
        }
        DotShape.Ring ->
            drawCircle(
                color = color,
                radius = s / 2f - 1f,
                style = Stroke(width = 1.5f),
            )
        DotShape.Chevron -> {
            val path =
                Path().apply {
                    moveTo(0f, s * 0.25f)
                    lineTo(s / 2f, s * 0.75f)
                    lineTo(s, s * 0.25f)
                }
            drawPath(path, color, style = Stroke(width = 1.5f))
        }
    }
}

/**
 * Builds the merged accessible name for the row. The name template is
 * read once per row recomposition; the dot cluster itself carries no
 * semantics (decorative).
 */
@Composable
fun buildCompactRowSemantics(
    appName: String,
    installedVersion: String,
    flags: CompactStatusFlags,
): String {
    val parts = buildList {
        add(appName)
        add(installedVersion)
        if (flags.readyToInstall) add(stringResource(Res.string.apps_compact_status_ready_to_install))
        if (flags.pendingInstall) add(stringResource(Res.string.apps_compact_status_pending_install))
        if (flags.variantStale) add(stringResource(Res.string.apps_compact_status_variant_stale))
        if (flags.variantPinned) add(stringResource(Res.string.apps_compact_status_variant_pinned))
        if (flags.filterActive) add(stringResource(Res.string.apps_compact_status_filter_active))
        if (flags.preReleaseOn) add(stringResource(Res.string.apps_compact_status_pre_release_on))
    }
    return parts.joinToString(", ")
}
