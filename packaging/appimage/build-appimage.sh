#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
version=${ZEPHYR_VERSION:-$(sed -n 's/^zephyrVersion=//p' "$project_root/gradle.properties")}

case "$(uname -m)" in
    x86_64)
        appimage_arch=x86_64
        release_arch=amd64
        tool_sha256=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
        ;;
    aarch64|arm64)
        appimage_arch=aarch64
        release_arch=arm64
        tool_sha256=f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158
        ;;
    *)
        echo "Unsupported AppImage architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

if [[ ${ZEPHYR_SKIP_GRADLE_BUILD:-0} != 1 ]]; then
    "$project_root/gradlew" -p "$project_root" \
        -PzephyrVersion="$version" \
        :desktopApp:createDistributable \
        --console=plain
fi

compose_image="$project_root/desktopApp/build/compose/binaries/main/app/com.worxbend.zephyr"
if [[ ! -x "$compose_image/bin/com.worxbend.zephyr" ]]; then
    echo "Compose application image is missing: $compose_image" >&2
    exit 1
fi

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
app_dir="$work_dir/Zephyr.AppDir"
mkdir -p \
    "$app_dir/usr/bin" \
    "$app_dir/usr/lib/zephyr" \
    "$app_dir/usr/share/applications" \
    "$app_dir/usr/share/icons/hicolor/scalable/apps" \
    "$app_dir/usr/share/metainfo"

cp -a "$compose_image/." "$app_dir/usr/lib/zephyr/"
install -m 0755 "$project_root/packaging/appimage/AppRun" "$app_dir/AppRun"
install -m 0644 "$project_root/packaging/linux/com.worxbend.zephyr.desktop" \
    "$app_dir/usr/share/applications/com.worxbend.zephyr.desktop"
install -m 0644 "$project_root/packaging/linux/com.worxbend.zephyr.svg" \
    "$app_dir/usr/share/icons/hicolor/scalable/apps/com.worxbend.zephyr.svg"
install -m 0644 "$project_root/packaging/linux/com.worxbend.zephyr.metainfo.xml" \
    "$app_dir/usr/share/metainfo/com.worxbend.zephyr.metainfo.xml"
install -m 0644 "$project_root/packaging/linux/com.worxbend.zephyr.metainfo.xml" \
    "$app_dir/usr/share/metainfo/com.worxbend.zephyr.appdata.xml"
ln -s usr/share/applications/com.worxbend.zephyr.desktop \
    "$app_dir/com.worxbend.zephyr.desktop"
ln -s usr/share/icons/hicolor/scalable/apps/com.worxbend.zephyr.svg \
    "$app_dir/com.worxbend.zephyr.svg"
ln -s com.worxbend.zephyr.svg "$app_dir/.DirIcon"
ln -s ../../AppRun "$app_dir/usr/bin/zephyr"

tool_dir="$project_root/build/appimage-tools"
tool="$tool_dir/appimagetool-$appimage_arch.AppImage"
mkdir -p "$tool_dir"
if [[ ! -f "$tool" ]] || ! echo "$tool_sha256  $tool" | sha256sum --check --status; then
    curl --fail --location --silent --show-error \
        "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-$appimage_arch.AppImage" \
        --output "$tool"
fi
echo "$tool_sha256  $tool" | sha256sum --check --status
chmod +x "$tool"

runtime="$tool_dir/runtime-$appimage_arch"
# Reuse the type-2 runtime from the checksum-pinned tool itself. Upstream's
# mutable `continuous` runtime release deletes old asset IDs, breaking rebuilds.
runtime_size=$("$tool" --appimage-offset)
[[ "$runtime_size" =~ ^[0-9]+$ ]] && [[ "$runtime_size" -gt 0 ]]
dd if="$tool" of="$runtime" bs=64K count="$runtime_size" iflag=count_bytes status=none
[[ $(stat -c %s "$runtime") -eq "$runtime_size" ]]

output_dir="$project_root/dist"
output="$output_dir/Zephyr-$version-linux-$release_arch.AppImage"
mkdir -p "$output_dir"
ARCH="$appimage_arch" "$tool" --appimage-extract-and-run \
    --runtime-file "$runtime" \
    "$app_dir" \
    "$output"
chmod +x "$output"
echo "$output"
