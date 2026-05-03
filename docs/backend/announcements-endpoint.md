# Announcements Endpoint — Backend Implementation Spec

This document specifies the backend work required to support the **Announcements** feature shipped on the client side. Hand to the backend team / agent. Self-contained.

## Context

The client (GitHub Store app) ships in-app announcements pulled from a single backend endpoint. Use cases include privacy-policy change notices, surveys, initiative endorsements (e.g. "Keep Android Open"), security advisories, and backend-status notices.

Privacy stance is non-negotiable: the endpoint receives no user identifier, returns the same payload to everyone, and the backend cannot link sequential requests to a specific user. All read/dismiss state is recorded only on the device.

The client is already merged. Any backend that returns a well-formed empty list at the spec'd endpoint will satisfy the client; content authoring tooling can come later.

---

## 1. Endpoint

```
GET /v1/announcements
```

- No authentication.
- No required headers (client sends standard Ktor `User-Agent`).
- No query parameters in v1.
- `Cache-Control: public, max-age=600` (10 min).
- `ETag` / `If-None-Match` support recommended (cuts payload and bandwidth on revalidation).
- CORS not required — only the app calls this; web mirror lives on the website if/when added.

### Response — 200 OK

```json
{
  "version": 1,
  "fetchedAt": "2026-06-15T12:34:56Z",
  "items": [
    { /* AnnouncementDto, see schema */ }
  ]
}
```

### Response — empty

Return 200 with `items: []` when nothing is active. Never 404.

### Response — failure

Standard 5xx for backend errors. Client falls back to its local cache, logs the failure, and shows a faint "Couldn't refresh" caption in the inbox header. No retry storm — single attempt per cold start with a 1h backoff on 5xx.

---

## 2. Item schema

Each announcement is one object in `items`. The client deserializes via the following Kotlin DTO (this is the contract — keep field names and types stable):

```kotlin
data class AnnouncementDto(
    val id: String,                                   // required, stable, kebab-case recommended
    val publishedAt: String,                          // required, ISO 8601 UTC, e.g. "2026-06-15T00:00:00Z"
    val expiresAt: String? = null,                    // optional, ISO 8601 UTC; client hides past items
    val severity: String,                             // "INFO" | "IMPORTANT" | "CRITICAL" — case-insensitive
    val category: String,                             // "NEWS" | "PRIVACY" | "SURVEY" | "SECURITY" | "STATUS"
    val title: String,                                // required, ≤ 80 chars
    val body: String,                                 // required, ≤ 600 chars
    val ctaUrl: String? = null,                       // optional external URL
    val ctaLabel: String? = null,                     // optional CTA button label
    val dismissible: Boolean = true,                  // false reserved for security/privacy notices
    val requiresAcknowledgment: Boolean = false,      // when true, "Dismiss" becomes "I've read this"
    val minVersionCode: Int? = null,                  // inclusive; client-side filter
    val maxVersionCode: Int? = null,                  // inclusive; client-side filter
    val platforms: List<String>? = null,              // ["ANDROID", "DESKTOP"]; null = both
    val installerTypes: List<String>? = null,         // ["DEFAULT", "SHIZUKU"]; null = both
    val iconHint: String? = null,                     // "INFO" | "WARNING" | "SECURITY" | "CELEBRATION" | "CHANGE"
    val i18n: Map<String, AnnouncementLocaleDto> = emptyMap()
)

data class AnnouncementLocaleDto(
    val title: String? = null,
    val body: String? = null,
    val ctaUrl: String? = null,
    val ctaLabel: String? = null
)
```

### Locale resolution

The `title` / `body` / `ctaUrl` / `ctaLabel` fields at the top level are the **English defaults**. Localized variants live under `i18n` keyed by BCP-47 locale code (`en`, `zh-CN`, `ja`, `ko`, `pt-BR`, etc.).

Client resolution order:
1. `i18n[fullLocale]` (e.g. `zh-CN`)
2. `i18n[primaryLocale]` (e.g. `zh`)
3. Top-level defaults

Untranslated locales fall back to English silently. There is no client-side error for missing translations.

### Validation rules (enforce server-side)

- `id` — non-empty, ≤ 64 chars, kebab-case recommended (e.g. `2026-06-15-keep-android-open`).
- `publishedAt`, `expiresAt` — must parse as ISO 8601. Reject otherwise.
- `severity`, `category`, `iconHint` — must match the enum strings above (case-insensitive). Reject otherwise.
- `title` length ≤ 80 chars per locale variant. Reject otherwise.
- `body` length ≥ 50 chars and ≤ 600 chars per locale variant. The 50-char floor blocks "various improvements" filler.
- `requiresAcknowledgment = true` → `dismissible` must be `false` (you can't both dismiss and acknowledge in the same UX).
- `category = SECURITY` → `severity` must be `IMPORTANT` or `CRITICAL`.
- `category = PRIVACY` → `requiresAcknowledgment` must be `true` for any policy change item (legal requirement).
- `ctaUrl` if present — must be `https://` (no `http://`, no other schemes).
- `i18n[locale]` keys must be valid BCP-47 codes.
- Duplicate `id` across items → reject the whole payload, not just the duplicate.

Validation runs at write time (PR review / admin-tool save) AND at serve time (defensive). Reject malformed entries from the served payload entirely; do not return them with garbled data.

### Forward compatibility

- Adding new fields to the item DTO is fine — client uses `ignoreUnknownKeys = true`.
- Adding new `severity`, `category`, or `iconHint` enum values is fine — client maps unknown values to safe defaults (`INFO`, `NEWS`, `null` respectively).
- Renaming or removing fields is **breaking** — bump the response envelope `version` field. Client v1 will only read `version: 1` items; future clients can fan out.

---

## 3. Storage / authoring

Recommended: **filesystem-in-repo** approach. Each announcement is one JSON file in `announcements/<id>.json`. CI deploys the directory; serve endpoint reads the directory at startup and refreshes on file change (or on a scheduled tick).

Pros:
- Full git history of every announcement and translation.
- Translators submit PRs adding `i18n` blocks; same review flow as code.
- No admin UI needed for v1.
- Restoring a deleted announcement = `git revert`.

Cons:
- Requires backend redeploy or a file-watcher hot reload.
- No live editor for non-technical authors (acceptable trade-off; the maintainer is the author).

If you prefer a DB-backed model (Postgres `announcements` table), that's also fine — the client only sees the served payload, not the storage model. Keep the schema mirror of the DTO above plus an `i18n` JSONB column.

### Per-announcement file shape (filesystem option)

```
announcements/
  2026-06-15-keep-android-open.json
  2026-07-01-privacy-update.json
  2026-08-05-survey-q3.json
```

Each file IS the `AnnouncementDto` (single object, not wrapped in `items`). The endpoint glues them into the envelope at serve time.

### Active filter

At serve time, filter out items where `expiresAt < now`. Return all others. Do NOT filter by `minVersionCode` / `maxVersionCode` / `platforms` / `installerTypes` — that's client-side filtering (preserves anonymity; backend never knows what platform the requester is on).

### Sort order

Return items sorted by `publishedAt` descending (newest first). Client also sorts client-side defensively, so this is a soft requirement.

---

## 4. Authoring rubric (paste into backend repo CONTRIBUTING)

When proposing a new announcement, every author should answer "yes" to all of these before opening the PR:

- Is this worth interrupting users? If no, don't ship — put it on the website news page instead.
- Could a user reasonably react to this? If there is no user value or action, do not ship.
- Is the body editable down to ≤ 600 chars? If not, link out via `ctaUrl`.
- Is the severity matched to actual user impact? Default `INFO`. Use `CRITICAL` only when data, security, or app function is at risk — and pair it with `requiresAcknowledgment: true`.
- Is acknowledgment legally required (privacy policy change, ToS update)? Set `requiresAcknowledgment: true` and `dismissible: false`.
- Have you set `expiresAt`? Time-bound items (surveys, initiatives) should expire; evergreen news rarely belongs in this channel at all.

Cadence target: ≤ 1 non-security item per month. More than that and users learn to dismiss reflexively, which kills credibility for the next real announcement.

---

## 5. Privacy guardrails (non-negotiable)

The client's privacy promise rests on these. Violating them undermines the entire feature.

- The endpoint must NOT read or log any client-identifiable header value beyond what the standard HTTP access log already records (timestamp, IP, response status, response size).
- The endpoint must NOT set or read cookies.
- The response payload must be byte-identical for all callers in the same locale window. No per-IP, per-region, or per-anything customization. (Locale resolution is client-side; the server returns ALL locales in the `i18n` block.)
- No A/B variant header in v1. If split-delivery becomes desired later, do it via aggregate item-level counts (server-side), never per-user.
- Standard access-log retention should match the existing backend policy (per CLAUDE.md context, currently 7 days).

A privacy-policy paragraph for the website is suggested at the bottom of this doc.

---

## 6. Example payloads

### Empty (initial state, no announcements live)

```json
{
  "version": 1,
  "fetchedAt": "2026-06-01T00:00:00Z",
  "items": []
}
```

### Single info item with locale variants

```json
{
  "version": 1,
  "fetchedAt": "2026-06-15T12:34:56Z",
  "items": [
    {
      "id": "2026-06-15-keep-android-open",
      "publishedAt": "2026-06-15T00:00:00Z",
      "expiresAt": "2026-09-15T00:00:00Z",
      "severity": "INFO",
      "category": "NEWS",
      "title": "Backing Keep Android Open",
      "body": "GitHub Store supports the Keep Android Open initiative. Google's proposed sideloading restrictions for 2026 would make installing apps outside Play harder for everyone. Read the full statement and find out how to participate.",
      "ctaUrl": "https://github-store.org/news/keep-android-open",
      "ctaLabel": "Read more",
      "dismissible": true,
      "requiresAcknowledgment": false,
      "iconHint": "CHANGE",
      "i18n": {
        "zh-CN": {
          "title": "支持「保持 Android 开放」",
          "body": "GitHub Store 支持「保持 Android 开放」倡议。Google 在 2026 年提出的侧载限制会让所有人在 Play 商店之外安装应用更加困难。点击了解完整声明,并参与到这一行动中。",
          "ctaLabel": "阅读详情"
        },
        "ja": {
          "title": "「Keep Android Open」を支持します",
          "body": "GitHub Store は「Keep Android Open」イニシアチブを支持します。Google が 2026 年に提案したサイドローディング制限は、Play 以外でのアプリ導入を全ユーザーにとって難しくします。声明全文と参加方法をご覧ください。",
          "ctaLabel": "詳細を読む"
        }
      }
    }
  ]
}
```

### Critical security advisory

```json
{
  "version": 1,
  "fetchedAt": "2026-07-02T08:00:00Z",
  "items": [
    {
      "id": "2026-07-02-download-verify-gap",
      "publishedAt": "2026-07-02T08:00:00Z",
      "severity": "CRITICAL",
      "category": "SECURITY",
      "title": "Update to 1.8.x — download verification gap in 1.7.0–1.7.3",
      "body": "Versions 1.7.0 through 1.7.3 had a gap in download integrity verification under specific mirror configurations. No data was at risk; install integrity was. Update to 1.8.x or later as soon as possible. The full advisory has details on what was affected and how the fix works.",
      "ctaUrl": "https://github-store.org/news/2026-07-download-verify-advisory",
      "ctaLabel": "View advisory",
      "dismissible": false,
      "requiresAcknowledgment": true,
      "maxVersionCode": 14,
      "iconHint": "SECURITY"
    }
  ]
}
```

(Note `maxVersionCode: 14` — client-side filter so users on 1.8.x and later won't see this advisory at all.)

---

## 7. Implementation checklist

For the backend agent / implementer:

- [ ] Add the `/v1/announcements` route to the existing Ktor server.
- [ ] Wire in the storage source (filesystem dir or DB table — your call).
- [ ] Implement the validation rules from §2 at write time AND defensively at serve time.
- [ ] Set `Cache-Control: public, max-age=600` and `ETag` / `If-None-Match` handling.
- [ ] Verify request access logs do NOT include any custom headers from the client.
- [ ] Add a smoke test that hits the endpoint and validates the envelope schema.
- [ ] Seed with one initial entry (suggestion below) so day-1 users see a working feature.
- [ ] Update the website privacy policy with the paragraph in §8.
- [ ] Document the per-announcement authoring workflow in the backend repo's README or CONTRIBUTING.

Suggested day-1 seed announcement (English-only, optional):

```json
{
  "id": "2026-XX-XX-announcements-launched",
  "publishedAt": "2026-XX-XXT00:00:00Z",
  "severity": "INFO",
  "category": "NEWS",
  "title": "Announcements live",
  "body": "GitHub Store now ships an in-app announcements channel for cross-version updates that don't fit a release. We use it sparingly — privacy notices, surveys, occasional advocacy. You can mute categories you don't care about from the inbox top-right menu.",
  "ctaUrl": null,
  "dismissible": true,
  "requiresAcknowledgment": false,
  "iconHint": "INFO"
}
```

---

## 8. Privacy policy paragraph (for website)

Suggested wording — adjust to fit existing privacy policy voice:

> **Announcements feed.** GitHub Store fetches a public, anonymous feed at `https://api.github-store.org/v1/announcements` on launch. The endpoint receives no user identifier and returns the same payload to every caller. Whether you have read or dismissed an individual announcement is recorded only on your device; we do not record this server-side.

---

## 9. Out of scope (do not implement in v1)

- Per-user customization (no opt-in to specific categories on the server side; that's purely client-side mute).
- Push notification dispatch.
- Email subscription tied to announcements.
- Aggregate impression telemetry (defer to phase 2 if ever).
- A/B variant delivery.
- Admin UI for non-technical authors (filesystem PRs are fine for v1).
- Webhook / RSS mirror.

---

## 10. Open questions for the backend implementer

1. **Storage:** filesystem-in-repo vs Postgres? Default recommendation: filesystem unless there's a specific reason to put it in the DB.
2. **Hot reload:** if filesystem, do you watch the directory or refresh on a scheduled tick? File watching is cleaner; ticks are simpler.
3. **i18n source:** translators submit PRs editing the same JSON file (recommended), or one PR per locale variant in a separate `announcements/i18n/<locale>/<id>.json` dir? Single-file is simpler unless you anticipate heavy translator parallelism.
4. **CDN caching:** if there's a Cloudflare / Fastly layer in front of the backend, set the cache TTL in line with the `Cache-Control` header. 10-minute freshness is the target.
5. **Validation tooling:** a small CLI that validates a draft `<id>.json` against the schema before PR would catch most authoring mistakes early. Optional v1 nicety.

---

## 11. Why this shape (architectural rationale, for context)

If the backend agent wants to push back on any decision above, here is the reasoning:

- **One endpoint, full feed every time.** Avoids per-user logic on the backend. Simpler, more cacheable, more privacy-preserving.
- **Embedded i18n vs per-locale endpoint.** Single fetch — switching app language doesn't trigger a refetch. Bandwidth marginal at this scale (~70 KB worst case for 12 locales × 10 active items × ~600 char bodies).
- **Client-side targeting filters.** Server returns everything, client filters by version/platform/installer. This means the backend never knows which platform a request came from.
- **Critical → modal promotion.** `severity: CRITICAL` + `requiresAcknowledgment: true` is the only path the client takes for blocking modals. Use sparingly.
- **No notification permission.** The audience is FOSS and privacy-conscious. Asking for notification permission for what is essentially a "we want to talk to you" channel will be reflexively denied and erode trust.

The full client-side rationale is in the planning doc maintained alongside the original feature work; this backend doc is the authoritative interface contract.
