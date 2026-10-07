#!/usr/bin/env bash
# Native-only packaging; never invokes Gradle, installs a JDK, or uploads a snap.
# Canonical's documented core24 OCI builder and native multi-arch index:
# https://github.com/canonical/snapcraft-rocks/blob/main/README.rst
# action-build uses LXD plus SNAPCRAFT_BUILD_INFO=1; we retain the same build
# metadata and Snapcraft lifecycle, but isolate it in an unprivileged container.
set -euo pipefail

if [[ ${1:-} == --help ]]; then
    printf '%s\n' \
        'Usage: packaging/snap/build-snap.sh [OUTPUT_DIRECTORY]' \
        'Prerequisites: native Linux amd64/arm64, Docker daemon access,' \
        '  /usr/bin/python3 with PyYAML, squashfs-tools (unsquashfs).' \
        'First build ./gradlew :desktopApp:createDistributable with Corretto 21.' \
        'The prebuilt image must match gradle.properties and the host architecture.' \
        'No Gradle/JDK runs here; ZEPHYR_SKIP_GRADLE_BUILD=1 is accepted but unnecessary.' \
        'Default output: dist/Zephyr-VERSION-linux-ARCH.snap.' \
        'TMPDIR selects scratch space. ZEPHYR_SNAP_KEEP_WORK=1 retains build evidence.' \
        'This does not install or publish the snap. Store classic approval is still required.'
    exit 0
fi
[[ $# -le 1 && ${1:-} != -* ]] || { printf '%s\n' 'Invalid arguments; use --help.' >&2; exit 2; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
python=${PYTHON:-/usr/bin/python3}
for command in docker unsquashfs "$python"; do
    command -v "$command" >/dev/null || { printf 'Missing prerequisite: %s\n' "$command" >&2; exit 1; }
done
"$python" -c 'import yaml' || { printf '%s\n' 'Install python3-yaml (PyYAML).' >&2; exit 1; }
[[ $(uname -s) == Linux ]] || { printf '%s\n' 'Linux is required.' >&2; exit 1; }
case $(uname -m) in
    x86_64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) printf '%s\n' 'Only native amd64 and arm64 are supported.' >&2; exit 1 ;;
esac
output=${1:-"$root/dist"}
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd)
# Do not use /tmp for work; honour the caller's scratch directory.
scratch=${TMPDIR:-"${XDG_CACHE_HOME:-$HOME/.cache}/zephyr"}
mkdir -p -- "$scratch"
work=$(mktemp -d "$scratch/zephyr-snap.XXXXXXXX")
cleanup() {
    if [[ ${ZEPHYR_SNAP_KEEP_WORK:-0} == 1 ]]; then
        printf 'Snap build evidence: %s\n' "$work" >&2
    else
        rm -rf -- "$work"
    fi
}
trap cleanup EXIT
version=$("$python" "$root/packaging/snap/snap_support.py" prepare "$root" "$work/project" "$arch")
image=ghcr.io/canonical/snapcraft:8_core24@sha256:0443273552768a3230c2ede3aa47e567da0242bfbb0a7bb1283093208c404a0c
# The architecture is native, not QEMU cross-building. The only writable host
# mount is an isolated copy, never the repository, SDKMAN, or Docker socket.
# Destructive mode changes this disposable Ubuntu 24.04 container, not the host.
docker run --rm --platform "linux/$arch" \
    --mount "type=bind,source=$work/project,target=/project" \
    --workdir /project --env SNAPCRAFT_BUILD_INFO=1 \
    --env "HOST_UID=$(id -u)" --env "HOST_GID=$(id -g)" \
    --entrypoint /bin/bash "$image" -c '
        set -euo pipefail
        trap '\''chown -R "$HOST_UID:$HOST_GID" /project'\'' EXIT
        export PATH="/usr/libexec/snapcraft:$PATH"
        apt-get update
        snapcraft pack --destructive-mode --platform "$1" --output /project/result.snap
    ' bash "$arch"
"$python" "$root/packaging/snap/snap_support.py" validate "$work/project/result.snap" "$version" "$arch"
artifact="$output/Zephyr-$version-linux-$arch.snap"
# Only publish the output filename after metadata/content validation succeeds.
install -m 0644 "$work/project/result.snap" "$artifact"
printf 'Built and validated: %s\n' "$artifact"
