package zed.rainxch.core.domain.util

/**
 * Builds a filename safe for the local downloads directory that's also
 * unique across repositories.
 *
 * # Why this exists
 *
 * Two different repos can ship installers with the same filename
 * (`app.apk`, `release.apk`, `installer.exe` are all common). Without
 * a per-repo namespace prefix, downloading both clobbers the first
 * file, breaks the orchestrator's "active downloads by filename"
 * tracking, and can install the wrong APK on top of the right row.
 *
 * # Output format
 *
 *     <owner>_<repo>_<original-name>.<ext>
 *
 * Examples:
 *
 *     ente-io  / ente / ente-auth-3.2.5-arm64-v8a.apk
 *       → ente-io_ente_ente-auth-3.2.5-arm64-v8a.apk
 *
 *     APKMirror / SomeApp / app.apk
 *       → apkmirror_someapp_app.apk
 *
 *     d4rk7355608 / Apps_Manager / Apps Manager-v1.0.apk
 *       → d4rk7355608_apps_manager_apps_manager-v1.0.apk
 *
 * # Sanitization
 *
 * Every component is independently sanitized to:
 *  - Lowercase (filesystem case-folding portability — APFS folds, ext4
 *    doesn't, NTFS varies — folding eagerly avoids cross-platform
 *    surprises)
 *  - Strip path traversal: `..`, `/`, `\`
 *  - Replace whitespace and shell-unfriendly characters with `_`
 *  - Collapse runs of `_`
 *
 * The original extension is preserved separately so the system
 * installer's MIME-type detection still works (`.apk`, `.exe`, `.dmg`,
 * etc.).
 */
object AssetFileName {
    /**
     * Characters that need replacement. Path separators must go;
     * everything else is for sanity (whitespace, shell metacharacters,
     * Windows-illegal chars `< > : " | ? *`). The output keeps `-` and
     * letters/digits intact because those are the most common tokens
     * in real filenames.
     */
    private val FORBIDDEN = Regex("""[^a-z0-9.\-]""")

    private val MULTI_UNDERSCORE = Regex("_+")

    /**
     * The maximum length of any single component (owner, repo, name).
     * 64 chars × 3 components + 2 separators + extension stays
     * comfortably under the 255-byte filename limit on every
     * filesystem we target.
     */
    private const val MAX_COMPONENT_LEN = 64

    fun scoped(
        owner: String,
        repo: String,
        originalName: String,
    ): String {
        val safeOwner = sanitizeComponent(owner).take(MAX_COMPONENT_LEN)
        val safeRepo = sanitizeComponent(repo).take(MAX_COMPONENT_LEN)

        // Split extension off the original name first so we don't
        // mangle the dot. Extension carries semantic meaning (the
        // installer dispatches on it), so we keep it intact and just
        // sanitize the body.
        val originalLower = originalName.lowercase()
        val dotIndex = originalLower.lastIndexOf('.')
        val (body, ext) =
            if (dotIndex > 0 && dotIndex < originalLower.length - 1) {
                originalLower.substring(0, dotIndex) to originalLower.substring(dotIndex)
            } else {
                originalLower to ""
            }
        val safeBody = sanitizeComponent(body).take(MAX_COMPONENT_LEN)
        val safeExt = sanitizeExtension(ext)

        // Compose. Empty components are replaced with `unknown` so we
        // never produce a name like `__app.apk` (the multi-underscore
        // collapse would still produce `_app.apk`, which is uglier
        // than carrying a placeholder).
        val ownerPart = safeOwner.ifBlank { "unknown" }
        val repoPart = safeRepo.ifBlank { "unknown" }
        val bodyPart = safeBody.ifBlank { "asset" }

        val joined = "${ownerPart}_${repoPart}_$bodyPart$safeExt"
        return MULTI_UNDERSCORE.replace(joined, "_")
    }

    /**
     * True when [fileName] is in the scoped format produced by [scoped].
     * Used by the orchestrator to detect "is this already namespaced"
     * so callers can pass either form during the migration window.
     */
    fun isScoped(
        fileName: String,
        owner: String,
        repo: String,
    ): Boolean {
        val ownerPart = sanitizeComponent(owner).take(MAX_COMPONENT_LEN).ifBlank { "unknown" }
        val repoPart = sanitizeComponent(repo).take(MAX_COMPONENT_LEN).ifBlank { "unknown" }
        val expectedPrefix = MULTI_UNDERSCORE.replace("${ownerPart}_${repoPart}_", "_")
        return fileName.lowercase().startsWith(expectedPrefix)
    }

    private fun sanitizeComponent(input: String): String {
        if (input.isBlank()) return ""
        val lowered = input.lowercase()
        // Replace path separators *first* so they always become a
        // single `_` rather than getting eaten by FORBIDDEN's
        // greedy collapse.
        val withoutSeparators =
            lowered.replace('/', '_').replace('\\', '_')
        // Strip path-traversal sequences before letter sanitization;
        // a leading `..` would otherwise survive as `..` and the
        // OS would interpret it.
        val noDots = withoutSeparators.replace("..", "_")
        return FORBIDDEN.replace(noDots, "_")
    }

    private fun sanitizeExtension(ext: String): String {
        if (ext.isEmpty()) return ""
        // Extensions are short and character-restricted by convention.
        // We allow `.apk`, `.exe`, `.dmg`, `.deb`, `.rpm`, `.msi`,
        // `.pkg`, `.zip` and similar — anything weirder gets the
        // generic sanitizer applied.
        val cleaned = FORBIDDEN.replace(ext, "")
        // Re-prepend the dot if the regex stripped it.
        return if (cleaned.startsWith('.')) cleaned else ".$cleaned"
    }
}
