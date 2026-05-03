# What's-new entries

One JSON file per release, named after the Android `versionCode` it ships with.
Loaded at runtime by `WhatsNewLoaderImpl` and rendered in the in-app sheet and
history screen.

## File layout

```
files/whatsnew/<versionCode>.json              # default (English)
files-zh-rCN/whatsnew/<versionCode>.json       # zh-CN translation
files-ja/whatsnew/<versionCode>.json           # ja translation
files-<qualifier>/whatsnew/<versionCode>.json  # any other locale
```

Compose Multiplatform Resources resolves `files-<qualifier>` directories the
same way it resolves `values-<qualifier>` for strings, so the loader picks the
locale-matched variant automatically and falls back to the default English file
when a translation is missing.

## Schema

```json
{
  "versionCode": 16,
  "versionName": "1.8.1",
  "releaseDate": "2026-05-03",
  "showAsSheet": true,
  "sections": [
    {
      "type": "NEW",
      "bullets": [
        "Short, user-facing sentence under ~90 characters."
      ]
    },
    {
      "type": "IMPROVED",
      "bullets": []
    },
    {
      "type": "FIXED",
      "bullets": []
    }
  ]
}
```

`type` accepts `NEW`, `IMPROVED`, `FIXED`, or `HEADS_UP`.

`showAsSheet = false` keeps the sheet silent on first launch (the loader still
records the version as seen). Use it for bug-fix-only patches that have nothing
worth interrupting the user for — silent patches preserve credibility for the
next real release.

## Per-release author workflow

1. Add `core/presentation/src/commonMain/composeResources/files/whatsnew/<versionCode>.json`.
2. Append the new `versionCode` to `KnownWhatsNewVersionCodes.ALL` in
   `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/whatsnew/WhatsNewLoaderImpl.kt`.
3. Keep bullets short, factual, and editorial — no marketing voice.

## Translator workflow

1. Copy `files/whatsnew/<versionCode>.json` to
   `files-<your-locale-qualifier>/whatsnew/<versionCode>.json`.
2. Translate the bullet text only. Leave `versionCode`, `versionName`,
   `releaseDate`, `showAsSheet`, and the section `type` values untouched.
3. Open a PR — translations land independently of the release that introduced
   the entry, and English remains the fallback for any version the locale has
   not translated yet.
