package zed.rainxch.core.data.mirror

import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorStatus
import zed.rainxch.core.domain.model.MirrorType

object BundledMirrors {
    val ALL: List<MirrorConfig> =
        listOf(
            entry("direct", "Direct GitHub", null, MirrorType.OFFICIAL),
            entry("ghfast_top", "ghfast.top", "https://ghfast.top/{url}", MirrorType.COMMUNITY),
            entry("moeyy_xyz", "github.moeyy.xyz", "https://github.moeyy.xyz/{url}", MirrorType.COMMUNITY),
            entry("gh_proxy_com", "gh-proxy.com", "https://gh-proxy.com/{url}", MirrorType.COMMUNITY),
            entry("ghps_cc", "ghps.cc", "https://ghps.cc/{url}", MirrorType.COMMUNITY),
            entry("gh_99988866_xyz", "gh.api.99988866.xyz", "https://gh.api.99988866.xyz/{url}", MirrorType.COMMUNITY),
        )

    private fun entry(
        id: String,
        name: String,
        template: String?,
        type: MirrorType,
    ) = MirrorConfig(
        id = id,
        name = name,
        urlTemplate = template,
        type = type,
        status = MirrorStatus.UNKNOWN,
        latencyMs = null,
        lastCheckedAt = null,
    )
}
