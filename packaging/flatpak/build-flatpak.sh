#!/usr/bin/env bash
set -Eeuo pipefail

project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
fail() { printf 'Flatpak: %s\n' "$*" >&2; exit 1; }
[[ $# == 0 ]] || fail 'No arguments expected; set ZEPHYR_VERSION and ZEPHYR_SKIP_GRADLE_BUILD instead.'
[[ $(uname -s) == Linux ]] || fail 'Native Linux builds are required.'
version=${ZEPHYR_VERSION-}
if [[ ! ${ZEPHYR_VERSION+x} ]]; then
    while IFS='=' read -r key value; do
        [[ $key != zephyrVersion ]] || version=$value
    done < "$project_root/gradle.properties"
fi
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Expected a numeric major.minor.patch version, got: $version"
case ${ZEPHYR_SKIP_GRADLE_BUILD:-0} in
    0|1) ;;
    *) fail 'ZEPHYR_SKIP_GRADLE_BUILD must be 0 or 1.' ;;
esac

case "$(uname -m)" in
    x86_64)
        flatpak_arch=x86_64
        release_arch=amd64
        elf_machine='62 0'
        ;;
    aarch64|arm64)
        flatpak_arch=aarch64
        release_arch=arm64
        elf_machine='183 0'
        ;;
    *)
        echo "Unsupported Flatpak architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

for tool in flatpak flatpak-builder od; do
    command -v "$tool" >/dev/null || fail "Required tool is missing: $tool"
done

if [[ ${ZEPHYR_SKIP_GRADLE_BUILD:-0} != 1 ]]; then
    "$project_root/gradlew" -p "$project_root" \
        -PzephyrVersion="$version" \
        :desktopApp:createDistributable \
        --console=plain
fi

# Validate the reused native image before touching Flatpak remotes.
compose_image="$project_root/desktopApp/build/compose/binaries/main/app/com.worxbend.zephyr"
launcher="$compose_image/bin/com.worxbend.zephyr"
[[ -x $launcher ]] || fail "Compose application image is missing or not executable: $launcher"
for binary in "$launcher" "$compose_image/lib/runtime/lib/server/libjvm.so"; do
    [[ -s $binary ]] || fail "Compose image is incomplete: $binary"
    read -r -a header <<< "$(od -An -v -tu1 -N6 "$binary")"
    [[ ${header[*]} == '127 69 76 70 2 1' ]] || fail "Not a little-endian ELF64 binary: $binary"
    read -r -a machine <<< "$(od -An -v -tu1 -j18 -N2 "$binary")"
    [[ ${machine[*]} == "$elf_machine" ]] || fail "Image architecture does not match native $release_arch: $binary"
done
config="$compose_image/lib/app/com.worxbend.zephyr.cfg"
[[ -f $config ]] || fail "Compose application configuration is missing: $config"
image_version=
while IFS= read -r line; do
    case $line in
        java-options=-Djpackage.app-version=*) image_version=${line#java-options=-Djpackage.app-version=} ;;
    esac
done < "$config"
[[ $image_version == "$version" ]] || fail "Compose image version '$image_version' does not match requested '$version'."

runtime_repo=https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak remote-add --user --if-not-exists flathub "$runtime_repo"

build_root="$project_root/build/flatpak/$flatpak_arch"
build_dir="$build_root/build-dir"
repo_dir="$build_root/repo"
rm -rf -- "$build_dir" "$repo_dir"
mkdir -p "$build_root" "$project_root/dist"

flatpak-builder \
    --arch="$flatpak_arch" \
    --force-clean \
    --user \
    --assumeyes \
    --state-dir="$build_root/state" \
    --install-deps-from=flathub \
    --repo="$repo_dir" \
    "$build_dir" \
    "$project_root/packaging/flatpak/com.worxbend.zephyr.yml"

output="$project_root/dist/Zephyr-$version-linux-$release_arch.flatpak"
bundle="$build_root/Zephyr-$version-linux-$release_arch.flatpak"
rm -f -- "$bundle"
flatpak build-bundle \
    --arch="$flatpak_arch" \
    --runtime-repo="$runtime_repo" \
    "$repo_dir" \
    "$bundle" \
    com.worxbend.zephyr master
[[ -s $bundle ]] || fail 'flatpak build-bundle did not produce a nonempty bundle.'
mv -f -- "$bundle" "$output"
printf '%s\n' "$output"
