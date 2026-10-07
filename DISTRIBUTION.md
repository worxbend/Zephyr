# Linux distribution

Zephyr `1.2.2` is packaged natively on GitHub-hosted `amd64` and `arm64`
machines. Native builds matter because Compose Desktop bundles an
architecture-specific JVM and Skiko library; the build does not cross-compile
those components.

## Release outputs

| Format | Architectures | Publication |
| --- | --- | --- |
| AppImage | `amd64`, `arm64` | GitHub Releases |
| Snap | `amd64`, `arm64` | GitHub Releases and Snap Store `stable` |
| Flatpak bundle | `amd64`, `arm64` | GitHub Releases |

A pushed stable numeric tag (`vMAJOR.MINOR.PATCH`) triggers publication.
The tag must match `zephyrVersion` and the latest AppStream release. Each
architecture runs `check` and builds its bundled-JVM application with Corretto
21. Native Ubuntu 24.04 runners then package AppImage, Flatpak, and Snap in
six isolated jobs. There are no macOS or Windows release jobs.

Publication requires **all six packages** and successful tests on both
architectures. Every package is inspected for its application identity,
version, and actual ELF architecture, not just its filename. Snap confinement,
grade, and desktop entries are also checked. Missing, empty, unexpected, or
incorrect artifacts fail the release. `SHA256SUMS` and `BUILD-MANIFEST.txt`
describe the complete matrix.

GitHub assets are uploaded to a draft release, downloaded again, and checked
against the original checksums before the release becomes public. Already
published releases are not overwritten. A failed draft upload can be rerun.
Snap Store publication starts only after GitHub publication succeeds and
uploads those exact released bytes. A Store failure marks the workflow failed
but does not remove the verified GitHub assets.

Pull requests and pushes to `main` run application tests, packaging-contract
tests, and an actual amd64 AppImage build/inspection. To exercise the entire
two-architecture matrix without publishing, dispatch `release.yml` on a branch:

```shell
gh workflow run release.yml --ref main
```

Manual distribution runs only build, validate, and retain artifacts; they do
not publish. Commit/push the workflow changes before dispatching them. Existing
tags do not acquire updated workflows; never move an existing release tag.

## Local builds

Build the current machine's real single-file AppImage:

```shell
packaging/appimage/build-appimage.sh
```

Build the current machine's Flatpak bundle after installing the `flatpak`,
`flatpak-builder`, and `elfutils` packages. The script installs the required
Freedesktop runtime and SDK for the current user:

```shell
packaging/flatpak/build-flatpak.sh
```

Build the Snap from the already-built native application. Prerequisites are
Docker daemon access, `python3-yaml` for `/usr/bin/python3`, and
`squashfs-tools`. A digest-pinned, multi-architecture Canonical core24 Snapcraft
container stages Ubuntu runtime dependencies and patches classic executables;
it does not install a JDK or invoke Gradle in the container:

```shell
./gradlew :desktopApp:createDistributable
packaging/snap/build-snap.sh
```

Generated release files are written to `dist/`.

For a version bump, update `zephyrVersion` in `gradle.properties` and the latest
AppStream release in `packaging/linux/com.worxbend.zephyr.metainfo.xml` together.
Snap adopts the Gradle version automatically. Also update the About screen and
diagnostics support-bundle version labels. Test first, then commit and push a
new matching version tag to publish. Creating a tag is a separate release
operation, not part of merely configuring CI.

## AppImage design

Compose's `createDistributable`/`packageAppImage` output is a self-contained
application directory, despite the task name; it is not a single-file
AppImage. `packaging/appimage/build-appimage.sh` converts that directory to a
conforming AppDir with `AppRun`, desktop metadata, icon, and AppStream
metadata, then packages it using AppImage `appimagetool` 1.9.1. Both native tool
binaries are SHA-256 pinned. The type-2 runtime is extracted from the verified
tool itself, avoiding deleted asset IDs from upstream's mutable `continuous`
runtime release.

## Snap Store prerequisites

The Store title, description, support links, category, and upload-ready media
are documented in [`snap/STORE_LISTING.md`](snap/STORE_LISTING.md).

Zephyr requires classic confinement because its purpose is to operate on the
host's SDKMAN installation and host toolchains. Canonical manually reviews
classic snaps. Follow [`snap/STORE_REVIEW.md`](snap/STORE_REVIEW.md) to
register the name, request approval, and configure the scoped
`SNAPCRAFT_TOKEN` Actions secret (already configured in this repository). The
publishing step maps it to Snapcraft's `SNAPCRAFT_STORE_CREDENTIALS` environment
variable; build jobs never receive it. The credential must be a valid scoped
`snapcraft export-login` credential; its presence does not prove its permissions
or classic-confinement approval. Builds and GitHub publication do not depend on
Store approval. Tag releases publish automatically; to retry only Store
publication after approval or credential renewal, use the already-verified
GitHub assets without rebuilding them:

```shell
gh workflow run publish-snap-store.yml -f release_tag=v1.2.2
```

The dedicated workflow downloads each architecture's `.snap` and
`SHA256SUMS` from that GitHub release, verifies the digest and embedded Snap
metadata and real ELF architecture, then publishes the two architectures
independently to `stable`. Public Store readback must show the expected version,
architecture, and exact uploaded SHA3-384 digest in `latest/stable`.
All versions share a Store-channel publication lock. Publication checks both
current stable architectures and refuses an older version, so an old manual
retry cannot silently downgrade the channel. Intentional rollbacks require a
separate operator-controlled Store operation.

## Flatpak scope and Flathub preparation

The release bundle wraps the same Compose application image used by the other
formats in the Freedesktop 25.08 runtime. It requests home access because
SDKMAN intentionally lives under the user's home directory, plus network,
display, notification, Secret Service, and host-spawn access.

Bundles include the Flathub runtime-repository URL so an installer can obtain
the Freedesktop runtime. Install a downloaded bundle with
`flatpak install --user ./Zephyr-VERSION-linux-ARCH.flatpak`. This is standalone
bundle distribution, not a Flathub listing or an automatic-update application
repository. The current launcher executes inside Flatpak: granting host-spawn
permission alone does not redirect SDKMAN operations to the host. Full host
toolchain integration needs separate runtime work and is not established by a
successful bundle build.
In particular, none of the currently supported terminal executables is present
in the runtime, so activated host-terminal launch is unsupported by this bundle.
Use AppImage or the classic Snap when that integration is required.

`packaging/flatpak/com.worxbend.zephyr.yml` is directly buildable for release
bundles. A future Flathub submission should instead compile from source inside
`flatpak-builder`. Since network access is disabled during module builds,
generate and commit the complete Gradle dependency source list with the
official `flatpak-builder-tools` Gradle generator, pin the source commit, and
submit the manifest to Flathub. The current prebuilt-image source is
deliberately not represented as Flathub-ready.

## Primary references

- [GitHub-hosted runner architectures](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
- [AppImage manual packaging](https://docs.appimage.org/packaging-guide/manual.html)
- [Snapcraft platforms and architectures](https://snapcraft.io/docs/reference/project-file/snapcraft-yaml)
- [Snap classic-confinement review](https://snapcraft.io/docs/reference/administration/reviewing-classic-confinement-snaps/)
- [Snap classic-confinement request template](https://forum.snapcraft.io/t/about-the-classic-confinement-category/43830)
- [Snapcraft publish action](https://github.com/snapcore/action-publish)
- [Flatpak manifests](https://docs.flatpak.org/en/latest/manifests.html)
- [Flatpak Gradle dependency generator](https://github.com/flatpak/flatpak-builder-tools/tree/master/gradle)
- [Freedesktop Platform 25.08](https://flathub.org/apps/org.freedesktop.Platform)
