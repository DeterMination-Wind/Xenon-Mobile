# Xenon Mobile Catalog Publishing

Xenon Mobile downloads Mindustry artifacts from the catalog committed at:

```text
http://play.mindustry.men/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

The catalog and artifact URLs use the Xenon mirror hosted at `play.mindustry.men`. GitHub Releases is the publishing backend only; the Hub does not fall back to GitHub at runtime. Older catalogs may still contain canonical GitHub URLs, which the Hub converts to the fixed mirror without retaining the GitHub URL as a download candidate.

## Runtime Contract

The Hub validates the catalog before showing an artifact. A valid v1 artifact contains:

- `variant`, `backend`, and the unique APK `slot` when applicable;
- `packageName`, `versionCode`, `versionName`, and `signatureSha256` for APKs;
- `sourceRepo`, `sourceCommit`, `releaseTag`, `build`, and `buildType`;
- mirror or canonical HTTPS `urls`, positive `size`, a 64-character `sha256`, and `nativeProfile = "arm64-v8a"`.

The current catalog may contain an empty `artifacts` array before the first release. The Hub shows an empty-state download page in that case. CI is responsible for replacing it with the 14 current game artifacts: 11 APK slots and 3 JAR variants.

Downloaded files are written below the app-owned Mindustry catalog cache. Size and SHA-256 are checked both after download and before use. APKs are then checked for package name, clone metadata, version, arm64 native code, signer, and downgrade status before a `PackageInstaller.Session` is committed.

APK installation is atomic. The system confirmation screen owns the final approval; cancellation or failure does not uninstall or replace the existing slot. The persisted session can be read after the Hub process is restarted.

## Catalog Shape

The catalog has `schemaVersion = 1`, the variants `vanilla`, `be`, and `mindustryx`, and these identity keys:

```text
APK: variant + backend + slot
JAR: variant + backend
```

APK package names are fixed:

```text
com.xenon.mobile.clone.vanilla.slot1 ... slot5
com.xenon.mobile.clone.mindustryx.slot1 ... slot5
com.xenon.mobile.clone.be.slot1
```

The catalog contains only the newest artifact for each identity. Previous versions remain available from their immutable GitHub Release assets and are not listed on the normal download page.

## Source Locks

`game-source-lock.json` is checked by `validateGameSourceLock`:

```text
Anuken/Mindustry     20da6a38ab0874b5d971bffede3995efd3da5d70
TinyLake/MindustryX  3b894f8518c1a36ec60f1f32af50a8b249d0f060
Anuken/MindustryServerList
                     f297264dc24621753bc008a18e17b582fa5e3f65
```

Runtime server lists use only the mirror's cached `servers_v8.json` or `servers_be.json` route. The lock above is for reproducible CI builds and parser fixtures.

## Local Validation

Use Java 17 and an Android SDK with API 36, build-tools 36, and NDK 25.2.9519653. A local debug keystore can be supplied through `DEBUG_KEYSTORE_PATH`, `DEBUG_STORE_PASSWORD`, `DEBUG_KEY_PASSWORD`, and `DEBUG_KEY_ALIAS`. The repository ignores local JKS and password files.

```powershell
.uild-xenon-mobile-debug.bat --no-daemon
.gradlew validateXenonMobileRelease
.gradlew :ZalithLauncher:testDebugUnitTest --no-daemon
```

The debug helper builds an arm64 Hub APK. It does not populate the catalog with fabricated hashes or sizes.

## Tag Release Workflow

`.github/workflows/release_ci.yml` runs for a `v*` tag and requires these GitHub Actions secrets:

```text
SIGNING_KEYSTORE_BASE64
STORE_PASSWORD
KEY_PASSWORD
RELEASE_KEY_ALIAS (optional; defaults to movtery_zalith)
```

The workflow:

1. Builds the signed arm64 Hub APK.
2. Checks out each locked game commit and builds the 11 arm64 clone APKs with the Xenon overlay.
3. Builds the Vanilla, BE, and MindustryX JAR artifacts.
4. Validates package metadata, version data, ABI, signer digest, size, and SHA-256.
5. Refuses a release when an APK slot versionCode or JAR build number is not greater than the previous catalog entry.
6. Generates `catalog/xenon-mobile-catalog.json` and validates it against the source lock.
7. Uploads APKs, JARs, source, patch, build-script, lock, notice, and catalog assets to the GitHub Release.
8. Commits the generated catalog to `main`.

Stable asset names are derived from the tag, variant, slot, and arm64 profile. Release asset names must never be reused for different bytes.

## Mirror Contract

The HTTP mirror maps these URL shapes to cached files:

```text
http://play.mindustry.men/github/raw/<owner>/<repo>/<branch>/<path>
http://play.mindustry.men/github/repos/<owner>/<repo>/releases/download/<tag>/<file>
http://play.mindustry.men/github/repos/<owner>/<repo>/releases/latest
http://play.mindustry.men/github/repos/Anuken/MindustryServerList/servers_v8.json
http://play.mindustry.men/github/repos/Anuken/MindustryServerList/servers_be.json
```

Catalog responses should be JSON with a short cache lifetime. APK and JAR responses must be direct binary responses with correct `Content-Length`, `Accept-Ranges`, and immutable caching. They must never return an HTML GitHub page.

The primary mirror route is currently HTTP. APK and JAR integrity checks remain mandatory. GitHub is not a runtime fallback source.

## Release Verification

After the release and mirror cache have refreshed, verify the catalog and one artifact from a device-accessible mirror endpoint:

```powershell
$catalog = "http://play.mindustry.men/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
curl.exe -L -I $catalog
curl.exe -L $catalog

$asset = "http://play.mindustry.men/github/repos/DeterMination-Wind/Xenon-Mobile/releases/download/vX.Y.Z/xenon-mobile-vanilla-slot1-vX.Y.Z-arm64.apk"
curl.exe -L -r 0-1023 -D .\range-headers.txt -o .\range-byte.bin $asset
curl.exe -L -o .\slot1.apk $asset
(Get-Item .\slot1.apk).Length
(Get-FileHash -Algorithm SHA256 .\slot1.apk).Hash.ToLowerInvariant()
```

The downloaded size and digest must match the corresponding catalog entry. On Android, install confirmation, cancellation, signer mismatch, downgrade, unsupported ABI, storage exhaustion, and unknown-source restrictions are surfaced as distinct install states.
