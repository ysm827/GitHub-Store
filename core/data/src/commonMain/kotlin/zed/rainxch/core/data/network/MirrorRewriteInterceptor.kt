package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey

/**
 * Marks a request to bypass [installMirrorRewrite] — used by the
 * direct branch of the multi-source download race in Task 11.
 */
val NO_MIRROR_REWRITE: AttributeKey<Boolean> = AttributeKey("NoMirrorRewrite")

/**
 * Installs the mirror-rewrite hook on a Ktor [HttpClient]. Call after
 * the client is built but before any request is fired.
 *
 * The hook checks (in order):
 *  1. `NO_MIRROR_REWRITE` attribute — bypass if true.
 *  2. [MirrorRewriter.shouldRewrite] — only rewrite GitHub-owned hosts.
 *  3. [ProxyManager.currentMirrorTemplate] — only rewrite when a
 *     non-Direct preference resolves to a non-null template.
 */
fun HttpClient.installMirrorRewrite() {
    plugin(HttpSend).intercept { request ->
        if (!request.attributes.contains(NO_MIRROR_REWRITE)) {
            val original = request.url.buildString()
            if (MirrorRewriter.shouldRewrite(original)) {
                val template = ProxyManager.currentMirrorTemplate()
                if (template != null) {
                    val rewritten = MirrorRewriter.applyTemplate(template, original)
                    request.url.takeFrom(Url(rewritten))
                }
            }
        }
        execute(request)
    }
}
