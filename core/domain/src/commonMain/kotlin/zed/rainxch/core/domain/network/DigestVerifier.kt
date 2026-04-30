package zed.rainxch.core.domain.network

interface DigestVerifier {
    /**
     * Streams the file at [filePath] through SHA-256 and compares against
     * [expectedDigest] (which may carry a `sha256:` prefix or be raw hex).
     *
     * @return null on match, a non-null human-readable reason on
     *   mismatch / IO error.
     */
    suspend fun verify(
        filePath: String,
        expectedDigest: String,
    ): String?
}
