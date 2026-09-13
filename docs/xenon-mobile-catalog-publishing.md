# Xenon Mobile Catalog Publishing

Xenon Mobile downloads Mindustry artifacts from the catalog committed at:

```text
http://121.199.60.4/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

The Xenon mirror is reached through its direct IP `http://121.199.60.4/github` as the first download source. The `mindustry.men` domains answer the hosting provider's ICP filing rejection page (`Non-compliance ICP Filing`), so the Hub defaults to the IP; the domain is kept as the second candidate and the base is configurable at build time through `-Pmindustry_mirror=<url>`. The Android app permits cleartext traffic, which is what makes the plain HTTP IP endpoint usable. GitHub Releases is the publishing backend and the last-resort fallback: the Hub appends the canonical GitHub URL for every resource and only requests it after every configured mirror attempt failed. Older catalogs may still contain canonical GitHub or domain URLs, which the Hub rewrites to the configured mirror while still keeping the GitHub URL as the final candidate.

## Runtime Contract

The Hub validates the catalog before showing an artifact. A valid v1 artifact contains:

- `variant`, `backend`, and the unique APK `slot` when applicable;
- `packageName`, `versionCode`, `versionName`, and `signatureSha256` for APKs;
- `sourceRepo`, `sourceCommit`, `releaseTag`, `build`, and `buildType`;
- mirror or canonical HTTPS `urls`, positive `size`, a 64-character `sha256`, and `nativeProfile = "arm64-v8a"`.

The current catalog contains the 14 published game artifacts: 11 APK slots and 3 JAR variants.

### Release Build

The catalog is parsed with Gson reflection, so the release build must keep the model:
`proguard-rules.pro` keeps `MindustryCatalogManifest`, `MindustryArtifact`, `CatalogMirror` and the
`MindustryVariant` / `MindustryBackend` enums (names, fields and constructors) plus the `Signature`
attribute for the generic lists. Without those rules R8 rewrites the model into an abstract shell and
drops the no-arguments constructor, and every device fails with
`Abstract classes can't be instantiated! ... Class name: mq5` while loading the catalog.
`MindustryCatalogR8RulesTest` fails the build when a model is missing from the rules.

### Runtime Source Order

For the catalog, every artifact and both server lists the Hub builds an ordered candidate list:

```text
1. configured Xenon mirror, default http://121.199.60.4/github
2. the play.mindustry.men domain (currently rejected by the ICP filing filter)
3. canonical GitHub source (last resort, HTTPS only)
```

Catalog manifest: `https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json`
Release asset: `https://github.com/DeterMination-Wind/Xenon-Mobile/releases/download/<tag>/<file>`
Server lists: `https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json` and `servers_be.json`

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
Anuken/Mindustry     89527f879b535b752376d9b935172be576320b59
TinyLake/MindustryX  dc388e903e3be54b386787785ad5c15d589bea90
Anuken/MindustryServerList
                     10c099d68349bfde95ec60aea1b76dc7135e55bd
```

Runtime server lists prefer the mirror's cached `servers_v8.json` or `servers_be.json` route and fall back to the canonical GitHub raw file. The lock above is for reproducible CI builds and parser fixtures.

## Upstream Readiness

Clone slots always follow the newest upstream release. `.github/workflows/upstream_sync_ci.yml`
runs **daily at 03:00 UTC** (and on demand) and does the whole follow-up in one job:

1. resolve the newest `Anuken/Mindustry`, `TinyLake/MindustryX` and `Anuken/MindustryServerList`
   commits with the GitHub CLI;
2. rewrite the pins in `game-source-lock.json`, `build.gradle.kts` and
   `.github/workflows/release_ci.yml`;
3. verify the refreshed pins, commit them and push `main`;
4. call `scripts/xenon-mobile/tag-release.py`, which tags the next
   `v0.0.0-ci-<date>-r<N>` release **only** when the published catalog artifacts are still built
   from an older source commit, and then starts `release_ci.yml`.

Step 4 needs `gh workflow run --ref <tag>` because a tag pushed with the default `GITHUB_TOKEN`
does not start a new workflow run. `release_ci.yml` therefore also accepts `workflow_dispatch`, and
its first job refuses any ref that is not a `v*` tag so a manual UI run cannot publish a branch-named
release.

`tag-release.py` also refuses to tag while an earlier tag has not reached the catalog yet (a release
in flight or failed), which keeps the daily job idempotent. A failed release therefore needs a manual
`gh run rerun` or a `--force` dispatch; the readiness report keeps showing the `published` gap until
the catalog catches up.

Everything can be inspected or run locally:

```powershell
python scripts/xenon-mobile/check-upstream-readiness.py
python scripts/xenon-mobile/update-upstream-pins.py            # dry run
python scripts/xenon-mobile/update-upstream-pins.py --write    # apply
python scripts/xenon-mobile/tag-release.py                     # dry run
.\gradlew validateXenonMobileRelease
```

`check-upstream-readiness.py` compares the newest upstream commits with the source lock, verifies
that the lock, `build.gradle.kts` and the release workflow pin the same commits, and checks that the
newest Xenon Mobile release plus the catalog on `main` expose every artifact with a matching size
and SHA-256. It exits `1` when an upstream release is not yet built into a Xenon Mobile release.

Findings use the scopes `pins` (the three files agree), the variant names `vanilla` / `be` /
`mindustryx` (upstream versus the pin), `serverList` (server list fixture), `catalog`, `release`
(release and catalog asset integrity) and `published` (the artifacts a device downloads were built
from the pinned commits). `published` stays a gap until a new release is published, so the daily
sync gates only on the other scopes:

```powershell
python scripts/xenon-mobile/check-upstream-readiness.py --ignore-scopes published
```

## Local Validation

Use Java 17 and an Android SDK with API 37, build-tools 36, and NDK 25.2.9519653. A local debug keystore can be supplied through `DEBUG_KEYSTORE_PATH`, `DEBUG_STORE_PASSWORD`, `DEBUG_KEY_PASSWORD`, and `DEBUG_KEY_ALIAS`. The repository ignores local JKS and password files.

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

The workflow runs for a `v*` tag (or an explicit `gh workflow run --ref <tag>`), and its first job
refuses any other ref. It then:

1. Builds the signed arm64 Hub APK.
2. Checks out each locked game commit and builds the 11 arm64 clone APKs with the Xenon overlay.
3. Builds the Vanilla, BE, and MindustryX JAR artifacts.
4. Validates package metadata, version data, ABI, signer digest, size, and SHA-256.
5. Refuses a release when an APK slot versionCode or JAR build number is not greater than the previous catalog entry.
6. Generates `catalog/xenon-mobile-catalog.json` and validates it against the source lock.
7. Renders the player-facing Release body with `scripts/xenon-mobile/release-notes.py`. Auto-generated
   commit lists never told a player what to install, so the body names the single file an Android
   player needs (`xenon-mobile-hub-<tag>-arm64.apk`) and then explains the isolated clone APKs, the
   desktop JARs and the audit assets.
8. Uploads APKs, JARs, source, patch, build-script, lock, notice, and catalog assets to the GitHub Release.
9. Commits the generated catalog to `main`.

The clone and JAR jobs do not download Arc from JitPack: they fetch `Anuken/Arc` at the `archash`
recorded in Mindustry's `gradle.properties` into an `Arc` directory next to the Mindustry checkout
and let Mindustry's `localArc` composite build compile it. JitPack currently answers `401` for the
`com.android` backend artifact, and Mindustry's own `settings.gradle` calls the local Arc checkout
"highly recommended, as jitpack is unreliable".

Stable asset names are derived from the tag, variant, slot, and arm64 profile. Release asset names must never be reused for different bytes.

## Mirror Contract

The mirror maps these URL shapes to cached files. The direct IP serves them over HTTP (`http://121.199.60.4/github`); the `play.mindustry.men` domain serves the same routes over HTTPS but is currently answered with the ICP filing rejection page:

```text
http://121.199.60.4/github/raw/<owner>/<repo>/<branch>/<path>
http://121.199.60.4/github/repos/<owner>/<repo>/releases/download/<tag>/<file>
http://121.199.60.4/github/repos/<owner>/<repo>/releases/latest
http://121.199.60.4/github/repos/Anuken/MindustryServerList/servers_v8.json
http://121.199.60.4/github/repos/Anuken/MindustryServerList/servers_be.json
```

Catalog responses should be JSON with a short cache lifetime.

The cache is refreshed by the mirror host's own scheduler (`github-cache.timer`, hourly) and it only
understands catalog URLs whose path is `/github/repos/<owner>/<repo>/releases/download/<tag>/<file>`;
the host in front of that path may be the deployment IP or the domain. A new release is therefore
invisible to devices until that job runs, so `check-upstream-readiness.py` reports a `mirror` finding
while the mirror still serves the previous tag. The mirror keeps only the newest Xenon Mobile tags
because every release is around 1.2 GB. APK and JAR responses must be direct binary responses with correct `Content-Length`, `Accept-Ranges`, and immutable caching. They must never return an HTML GitHub page.

The mirror keeps its routes stable across the IP and the domain, so rewriting a published URL is only a host change. APK and JAR integrity checks remain mandatory, so a fallback can never change the downloaded bytes. GitHub is the final runtime fallback after all mirror attempts.

## Release Verification

After the release and mirror cache have refreshed, verify the catalog and one artifact from a device-accessible mirror endpoint:

```powershell
$catalog = "http://121.199.60.4/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
curl.exe -L -I $catalog
curl.exe -L $catalog

$asset = "http://121.199.60.4/github/repos/DeterMination-Wind/Xenon-Mobile/releases/download/vX.Y.Z/xenon-mobile-vanilla-slot1-vX.Y.Z-arm64.apk"
curl.exe -L -r 0-1023 -D .\range-headers.txt -o .\range-byte.bin $asset
curl.exe -L -o .\slot1.apk $asset
(Get-Item .\slot1.apk).Length
(Get-FileHash -Algorithm SHA256 .\slot1.apk).Hash.ToLowerInvariant()
```

The downloaded size and digest must match the corresponding catalog entry. On Android, install confirmation, cancellation, signer mismatch, downgrade, unsupported ABI, storage exhaustion, and unknown-source restrictions are surfaced as distinct install states.
