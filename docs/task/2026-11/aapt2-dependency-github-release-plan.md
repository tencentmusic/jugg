# AAPT2 pinned dependency GitHub Release archive plan

Purpose: Move pinned AAPT2 source archives from machine-local storage to immutable GitHub Release Assets in `tencentmusic/jugg`, while keeping dependency selection and integrity verification in Git.
Decision: Store manifests, commit pins, checksums and download logic in Git; store the archive bytes in a dedicated dependency Release tagged under `aapt2-deps/`; keep `/Volumes/android` only as a migration source and local cache.
Impact: Reproducible builds can download the same dependency set without a mounted AOSP checkout. Existing source archives are not added to the Git repository, and a dependency revision creates a new immutable Release tag instead of replacing an existing asset.

## 1. Goal and scope

This plan covers the pinned source archives currently used by the standalone AAPT2 build:

- `androidfw`
- `idmap`
- `base`
- `utils`
- `cutils`
- `log`
- `zip`
- `system`
- `buildversion`
- `native`
- `gtest`
- `protobuf`
- `incfs`
- `fmt`

The plan does not publish a complete AOSP checkout, `out/soong` build outputs, compiler toolchains, or platform SDKs. Those remain platform-specific build inputs and must be handled separately.

## 2. Repository and Release layout

Git stores the dependency contract:

- `aapt2-inclink/scripts/dependencies.json`
- the full source commit in every archive URL
- byte-level `sha256`
- stable `content_sha256` where Gitiles metadata is non-reproducible
- the downloader and validation logic

GitHub Release Assets store the archive bytes. Use a dedicated tag and Release:

```text
Tag:   aapt2-deps/android-14.0.0_r75
Title: AAPT2 dependencies for android-14.0.0_r75
```

When the dependency set changes, create a new immutable tag qualified by the source release, rather than appending a `vN` suffix. Never overwrite an existing dependency asset or move an existing dependency tag.

The Release contains:

```text
MANIFEST.sha256.txt
androidfw-<commit>.tar.gz
idmap-<commit>.tar.gz
base-<commit>.tar.gz
utils-<commit>.tar.gz
cutils-<commit>.tar.gz
log-<commit>.tar.gz
zip-<commit>.tar.gz
system-<commit>.tar.gz
buildversion-<commit>.tar.gz
native-<commit>.tar.gz
gtest-<commit>.tar.gz
protobuf-<version-or-commit>.tar.gz
incfs-<commit>.tar.gz
fmt-<commit>.tar.gz
```

The archive filename must remain stable after `dependencies.json` is updated. A new checksum or source revision uses a new Release tag.

## 3. Migration from the current archive

Use `/Volumes/android/jugg-aapt2-deps14-archive` as the migration source only after checking its manifest:

```bash
shasum -a 256 /Volumes/android/jugg-aapt2-deps14-archive/MANIFEST.sha256.txt
```

The expected current manifest checksum is:

```text
4d9554e0c3e8c1d91b7666259b727662bdad9695bb1c7d511ca4d77ea1f0a195
```

Before upload:

1. Verify every archive against the manifest.
2. Verify the archive entry/content hashes used by `dependencies.json`.
3. Confirm the archive list contains no generated `out/soong` files or local build products.
4. Create the dedicated tag and Release.
5. Upload all archives and `MANIFEST.sha256.txt` as Release Assets.
6. Download the Assets into a new empty cache and repeat archive and content validation.
7. Update `dependencies.json` URLs to the Release Asset URLs in one change.
8. Rebuild from an empty dependency and CMake cache before removing the old machine-local archive dependency.

The upload can be performed with GitHub CLI by a maintainer who has repository contents write permission:

```bash
git tag aapt2-deps/android-14.0.0_r75
git push origin aapt2-deps/android-14.0.0_r75
gh release create \
  'aapt2-deps/android-14.0.0_r75' \
  /Volumes/android/jugg-aapt2-deps14-archive/*.tar.gz \
  /Volumes/android/jugg-aapt2-deps14-archive/MANIFEST.sha256.txt \
  --title 'AAPT2 dependencies for android-14.0.0_r75' \
  --notes-file /tmp/aapt2-deps-release-notes.md
```

The Release notes must record the Android release tag, AAPT2 commit, manifest checksum, archive count, and the purpose of the archive set.

## 4. URL and integrity contract

After publication, each `dependencies.json` URL uses this form:

```text
https://github.com/tencentmusic/jugg/releases/download/aapt2-deps/android-14.0.0_r75/<asset-name>
```

The downloader must preserve the current validation order:

1. Download to a temporary file.
2. Verify the byte-level SHA-256 when it is stable.
3. If Gitiles metadata changes the compressed-byte hash, verify `content_sha256` instead.
4. Reject changed archive content.
5. Extract only after validation.
6. Mark extraction complete only after the destination is valid.

No download failure may silently fall back to `/Volumes/android`, Homebrew, a system library, or an unpinned URL.

## 5. CI and access

For a public repository, Release Asset URLs can be downloaded directly. For a private repository or private Release, the build worker needs authenticated read access through the repository's CI identity. Do not put a personal access token in `dependencies.json` or shell arguments.

The Release upload requires a maintainer or workflow identity with repository contents write permission. A future publication workflow may use the repository-provided `GITHUB_TOKEN`, but the first migration should be performed manually and reviewed before automation is added.

Actions Artifacts are not the primary storage for this archive set because they are intended for temporary workflow outputs and have retention rules. They may be used for upload staging, but the final source of truth is the immutable GitHub Release Asset.

## 6. Acceptance criteria

- Every dependency archive in `dependencies.json` is downloadable from the dedicated Release.
- A fresh empty cache validates all archives without `/Volumes/android`.
- Stable content hashes still reject modified archive contents.
- The build does not require a complete AOSP checkout for the source-archive stage.
- The Release manifest checksum matches the value recorded in the task record.
- A second worker can reproduce extraction and CMake configuration from the Release URLs.
- Existing dependency tags and assets remain unchanged after publication.

## 7. Rollback

If migration validation fails, revert the `dependencies.json` URL change to the previous pinned URLs and retain the new Release for investigation. Do not delete or replace the old archive set until a successful empty-cache rebuild has been recorded.

## 8. Follow-up boundary

The standalone CMake graph now builds libpng, zlib and expat from the pinned archives, builds protobuf 3.20.3 from its pinned source, and generates AAPT2 protobuf sources locally. The build entry point has no AOSP, Homebrew, or local archive fallback. The platform C++ runtime remains an explicit toolchain input; the historical AOSP libc++ archive was not retained as an active dependency because its Android-era runtime fallback aborts on the current Apple Clang/macOS runtime.

## 9. Migration record

The merged archive set is published to `tencentmusic/jugg` Release tag `aapt2-deps/android-14.0.0_r75` with 19 assets: 17 active build inputs plus the historical `libcxx` and `protobuf-aosp` archives. The merged manifest SHA-256 is `23f0190f20c190d05d4c880963f4326f35bfb0c5b1be421b7fa7b92e0ec95c9d`. A fresh cache populated only from the Release Asset URLs must download and extract all 17 entries in `dependencies.json` and pass the existing byte-level/content hash checks. The former `aapt2-deps/android-14.0.0_r75-v2` Release and tag were deleted; the URLs in `aapt2-inclink/scripts/dependencies.json` now point to the single immutable Release. `/Volumes/android` is no longer needed for this source-archive stage.

## 10. Next local-build dependency pins

The AOSP checkout supplied the following source commits for local-build dependencies during migration:

| Dependency | AOSP commit | Archive SHA-256 | Stable content SHA-256 |
|---|---|---|---|
| libpng | `944c6ff6487120af53c52a626a666a09d20153e2` | `59747d7135d243088b015abcea8d853247af608b59aaf5f59a8eb42a6f8fbfce` | `2a9fe133eb2abf4138e517677e0194b044ba2020f7643a2944c140de4b818c13` |
| zlib | `bad63664301d80463fde8fb48ca81534b43c219c` | `44e49be82f68d4d49f4970493fbadd9fd694e559fa623ea02261fa947ff735ed` | `df00b4b6bbf91819ee4d2de7e75da9fac1bd18dab49c27475398f0f384d3a8f2` |
| expat | `e99bcc1aae9ec06c34808d2e980ae6a626bb2366` | `9e9b94a510b97651845dc916f0331b7537cff9b140b0644ba9fe42c95aa2be03` | `ae002ddd5093dd6ba908fc9a88cf3add6c573dbef667563a28319b5945409fe4` |
| protobuf | `fece1c3e7124f940e00502a5431726ec2cd3b17a` | `914b3d2cc6f922fcccc092a3416784473b180e2ec459562db9adba7bda214a58` | `13451239fa479c6105ba2eadcaa9f69b043d03783188a001970e71b0e1037196` |

The libpng, zlib, expat and protobuf pins are consumed by the standalone CMake graph and published through the merged dependency Release. The libcxx pin remains a migration record only; the active macOS build uses the selected Apple Clang platform C++ runtime.

## 11. Standalone build completion

The remaining AOSP checkout dependency has been removed from the macOS build path. `CMakeLists.txt` always consumes the pinned local source tree, generates protobuf code locally, and links the compression libraries built in the same graph. `build_macos.py` no longer accepts `--aosp-root`, requires Homebrew libpng, or restricts arm64/universal builds to a mounted checkout.

Validation completed on 2026-11-10 with an empty cache populated only from the GitHub Release assets: x86_64, arm64, and universal Release builds completed. The resulting universal binary contains `x86_64 arm64`; its dynamic dependencies are the platform `libSystem` and `libc++` runtime only. The version command runs successfully, and the release gate accepts these system runtime paths.
