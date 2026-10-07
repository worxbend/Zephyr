#!/usr/bin/env bash
# Compose/Skiko and the JDK's AWT libraries need these even for offscreen tests.
# Keep both native runner architectures independent of preinstalled image tools.
set -euo pipefail

python3 - <<'PY'
import ctypes
for library in ('libGL.so.1', 'libEGL.so.1', 'libX11.so.6', 'libfontconfig.so.1'):
    try:
        ctypes.CDLL(library)
        print(f'Before setup: {library} available')
    except OSError as error:
        print(f'Before setup: {error}')
PY

sudo apt-get update
sudo apt-get install --yes --no-install-recommends \
    libgl1 libegl1 libx11-6 libxext6 libxi6 libxrender1 libxtst6 \
    libfontconfig1 libfreetype6 libasound2t64 fonts-dejavu-core

# A failed native load here stops the job before Gradle masks its cause with
# cascading ExceptionInInitializerError/NoClassDefFoundError test failures.
python3 - <<'PY'
import ctypes
for library in ('libGL.so.1', 'libEGL.so.1', 'libX11.so.6', 'libfontconfig.so.1'):
    ctypes.CDLL(library)
    print(f'After setup: {library} available')
PY
