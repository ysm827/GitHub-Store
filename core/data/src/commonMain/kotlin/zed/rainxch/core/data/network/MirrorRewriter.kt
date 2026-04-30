package zed.rainxch.core.data.network

import io.ktor.http.Url

object MirrorRewriter {
    private val rewriteHosts =
        setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
        )

    /**
     * True iff the URL host is one of the GitHub-owned hosts that should
     * be routed through a community mirror. `api.github.com` is intentionally
     * excluded — community mirrors are built for binary downloads, not API
     * calls, and routing API traffic through them returns 403. `api.github-store.org`
     * (our backend) is also excluded.
     */
    fun shouldRewrite(url: String): Boolean =
        runCatching {
            val host = Url(url).host.lowercase()
            host in rewriteHosts
        }.getOrDefault(false)

    /**
     * Substitutes the literal `{url}` in the template with the full
     * GitHub URL. Caller is responsible for ensuring the template
     * contains exactly one `{url}` placeholder; that validation happens
     * at custom-mirror entry time.
     */
    fun applyTemplate(
        template: String,
        githubUrl: String,
    ): String = template.replace("{url}", githubUrl)
}
