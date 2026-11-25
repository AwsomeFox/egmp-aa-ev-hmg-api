#!/bin/sh
# The HMG-API mod SDK this repo builds with, and HMG-API's hook maps for the tests:
#   eval "$(sh hmgapi-mod/sdk.sh)"
# prints HMGAPI_SDK=<the unpacked SDK> and HMGAPI_MAPS=<HMG-API's maps/ at the commit the SDK
# was built from> (the SDK has no maps). Since 1.1.0-rc.9 HMG-API's source keeps the maps
# compact (maps/base/<app>.json plus each build's differences in its car.json); they are
# expanded here with that commit's own tools/maplib.py into the per-app files a car reads,
# which is what HMG-API's engine and engine-test.sh read. The SDK is the release hmgapi-sdk.json pins
# ({"version": ..., "sha256": ...}, the same pin HMG-API's reusable mod workflow reads),
# checked against that SHA-256. Both are cached in .hmgapi/ (not committed). A variable
# already set is kept: HMGAPI_SDK for an SDK you built (./build.sh sdk in HMG-API),
# HMGAPI_MAPS for a checkout's maps/. HMGAPI_REPO: where HMG-API is (Open-HMG/HMG-API).
set -e
cd "$(dirname "$0")/.."
REPO=${HMGAPI_REPO:-Open-HMG/HMG-API}
V=$(python3 -c 'import json; print(json.load(open("hmgapi-sdk.json"))["version"])')
SUM=$(python3 -c 'import json; print(json.load(open("hmgapi-sdk.json"))["sha256"])')
C=$PWD/.hmgapi/sdk-$V
if [ -z "$HMGAPI_SDK" ]; then
    if [ ! -f "$C/sdk/lib/hmgapi-engine.jar" ]; then
        mkdir -p "$C"
        curl -fsSL -o "$C/sdk.zip" "https://github.com/$REPO/releases/download/v$V/hmgapi-mod-sdk-$V.zip" >&2
        python3 - "$C/sdk.zip" "$SUM" <<'PY' >&2
import hashlib, sys
got = hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest()
if got != sys.argv[2]:
    sys.exit("hmgapi-mod-sdk: sha256 %s, but hmgapi-sdk.json pins %s" % (got, sys.argv[2]))
PY
        rm -rf "$C/sdk" && mkdir -p "$C/sdk" && unzip -q "$C/sdk.zip" -d "$C/sdk"
    fi
    HMGAPI_SDK=$C/sdk
fi
if [ -z "$HMGAPI_MAPS" ]; then
    B=$(head -c 40 "$HMGAPI_SDK/BUILD")
    if [ ! -f "$C/maps-$B/points-ok" ]; then
        rm -rf "$C/maps-$B" && mkdir -p "$C/maps-$B"
        curl -fsSL -o "$C/src.tar.gz" "https://codeload.github.com/$REPO/tar.gz/$B" >&2
        python3 - "$C/src.tar.gz" "$C/maps-$B" <<'PY' >&2
import os, sys, tarfile
t = tarfile.open(sys.argv[1])
n = 0
for m in t.getmembers():
    parts = m.name.split("/", 1)
    if len(parts) == 2 and (parts[1].startswith("maps/") or parts[1] == "tools/maplib.py") \
            and (m.isfile() or m.isdir()) and ".." not in m.name:
        m.name = parts[1]
        t.extract(m, sys.argv[2])
        n += m.isfile() and m.name.startswith("maps/")
if n == 0:
    sys.exit("no maps/ in HMG-API's source at that commit")
PY
        rm -f "$C/src.tar.gz"
        # compact maps (a maps/base/ folder): expand them as HMG-API's build.sh does
        if [ -d "$C/maps-$B/maps/base" ]; then
            python3 "$C/maps-$B/tools/maplib.py" expand "$C/maps-$B/full" "$C/maps-$B/maps" >&2
            rm -rf "$C/maps-$B/maps" && mv "$C/maps-$B/full" "$C/maps-$B/maps"
        fi
        touch "$C/maps-$B/points-ok"
    fi
    HMGAPI_MAPS=$C/maps-$B/maps
fi
echo "HMGAPI_SDK=$HMGAPI_SDK; export HMGAPI_SDK"
echo "HMGAPI_MAPS=$HMGAPI_MAPS; export HMGAPI_MAPS"
