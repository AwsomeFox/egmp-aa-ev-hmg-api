#!/bin/sh
# Build the mod's two builds (hmgapi-mod/variants.py) into build/mod/:
#   egmp-aa-ev/             requires the Wireless CarPlay + Android Auto mod (preconditioning)
#   egmp-aa-ev-standalone/  doesn't (everything but preconditioning)
# and, with a key, their signed packages dist/<id>-<version>.zip.
#
#   sh hmgapi-mod/build.sh                 the mod folders (unsigned)
#   sh hmgapi-mod/build.sh <author key>    plus the signed packages, through the SDK's mkmodpkg.sh
#
# Built with the HMG-API mod SDK that hmgapi-sdk.json pins (HMGAPI_SDK; hmgapi-mod/sdk.sh
# fetches it), a JDK and the Android SDK (build-tools, a platform android.jar; ANDROID_HOME).
# A new author key:
#   java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing keygen <key file>
#
# hooks.jar: the mod's code in GPM. The classes the NEMESIS build shares
# (gpm-ev/src: NavProto, PrecondCore, EvRowAgg, EvLogger, EvModelTemplate, RoadLearn,
# EvDriveLog) come from there unchanged, except EvDriveLog's package line in
# the log header; EvCar, EvModelSender and Precond are this mod's own
# (hmgapi-mod/src), with HmgApiEntry, HmgCar, NativeCheck and Relay. Both builds
# carry the same hooks.jar; the standalone one keeps preconditioning off.
# relay.jar: the relay service (full build only), with hmgapi-client.jar's classes inside.
set -e
cd "$(dirname "$0")/.."
REPO=$PWD
[ -n "$HMGAPI_SDK" ] || eval "$(sh hmgapi-mod/sdk.sh)"
[ -f "$HMGAPI_SDK/lib/hmgapi-runtime.jar" ] || { echo "no HMG-API mod SDK at $HMGAPI_SDK (HMGAPI_SDK)" >&2; exit 1; }
HMGAPI_SDK=$(cd "$HMGAPI_SDK" && pwd)
. "$HMGAPI_SDK/bin/env.sh"
RT=$HMGAPI_SDK/lib/hmgapi-runtime.jar:$HMGAPI_SDK/lib/hmgapi-runtime-stubs.jar
CLIENT=$HMGAPI_SDK/lib/hmgapi-client.jar
echo "== HMG-API mod SDK $(cat "$HMGAPI_SDK/VERSION")"
[ -x "$D8" ] && [ -f "$ANDROID_JAR" ] || { echo "Android SDK not found (ANDROID_HOME)" >&2; exit 1; }

ID=egmp-aa-ev
VER=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' hmgapi-mod/mod.json | head -1)
B=build/hmgapi
MOD=build/mod/$ID
SOLO=build/mod/$ID-standalone
PKG=com/google/android/projection/protocol
rm -rf "$B" "$MOD" "$SOLO"
mkdir -p "$B/src/$PKG" "$B/hooks" "$B/relay" "$MOD" "$SOLO"

echo "== hooks.jar"
for c in NavProto PrecondCore EvRowAgg EvLogger EvModelTemplate EvDriveLog RoadLearn; do
    cp "gpm-ev/src/$PKG/$c.java" "$B/src/$PKG/"
done
# the log header names the writer
sed -i.orig 's/static final String PKG = "aa.ev.model [^"]*";/static final String PKG = "egmp-aa-ev '"$VER"' (HMG-API)";/' "$B/src/$PKG/EvDriveLog.java"
grep -q "egmp-aa-ev $VER (HMG-API)" "$B/src/$PKG/EvDriveLog.java" || { echo "EvDriveLog PKG line not found" >&2; exit 1; }
rm -f "$B/src/$PKG/EvDriveLog.java.orig"
cp hmgapi-mod/src/$PKG/*.java "$B/src/$PKG/"
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$RT" -d "$B/hooks" \
    $(find "$B/src" -name '*.java') \
    gpm-ev/stubs/com/hkmc/cluster/NavigationCluster.java gpm-ev/stubs/com/hkmc/cluster/NavigationClusterListener.java
# our classes only: never the car's (stubs) or HMG-API's
"$D8" --release --min-api 19 --lib "$ANDROID_JAR" --classpath "$HMGAPI_SDK/lib/hmgapi-runtime.jar" \
    --classpath "$HMGAPI_SDK/lib/hmgapi-runtime-stubs.jar" \
    --classpath "$B/hooks" --output "$MOD/hooks.jar" $(find "$B/hooks/$PKG" -name '*.class')
for c in $(cd "$B/hooks" && find "$PKG" -name '*.class' | sed 's/\.class$//' | sort); do
    unzip -p "$MOD/hooks.jar" classes.dex | grep -q "L$c;" || { echo "class not in hooks.jar: $c" >&2; exit 1; }
done

echo "== relay.jar"
mkdir -p "$B/relay-classes"
(cd "$B/relay-classes" && jar xf "$CLIENT" hmgapi)
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$CLIENT" -d "$B/relay-classes" \
    $(find hmgapi-mod/service/src -name '*.java')
"$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$MOD/relay.jar" $(find "$B/relay-classes" -name '*.class')

echo "== mod folders"
python3 hmgapi-mod/variants.py full "$MOD" >/dev/null
python3 hmgapi-mod/variants.py standalone "$SOLO" >/dev/null
for d in "$MOD" "$SOLO"; do
    mkdir -p "$d/recipes" "$d/scripts"
    cp hmgapi-mod/recipes/*.recipe "$d/recipes/"
    cp hmgapi-mod/scripts/offload.sh "$d/scripts/"
    chmod 755 "$d/scripts/offload.sh"
done
cp "$MOD/hooks.jar" "$SOLO/hooks.jar"
# the offload service asks HMG-API for its USB folder by the mod's id
sed -i.orig "s/^MOD=$ID\$/MOD=$ID-standalone/" "$SOLO/scripts/offload.sh" && rm -f "$SOLO/scripts/offload.sh.orig"
grep -q "^MOD=$ID-standalone\$" "$SOLO/scripts/offload.sh" || { echo "offload.sh: MOD line not found" >&2; exit 1; }
for d in "$MOD" "$SOLO"; do
    if grep -l "$(printf '\r')" "$d/scripts/offload.sh" "$d"/*.json >/dev/null 2>&1; then
        echo "CRLF line endings in $d (the head unit's shell can't run them)" >&2
        exit 1
    fi
    echo "-- $d"
    (cd "$d" && find . -type f | sort | xargs md5sum 2>/dev/null || (cd "$d" && find . -type f | sort | xargs md5 -r))
done

if [ -n "$1" ]; then
    echo "== signed packages"
    mkdir -p dist
    for d in "$MOD" "$SOLO"; do
        # the SDK's packer signs with <key> and the <key>.pub beside it, then verifies
        OUT=$REPO/$B/pkg sh "$HMGAPI_SDK/bin/mkmodpkg.sh" "$REPO/$d" "$1" > "$B/mkmodpkg.log" 2>&1 || { cat "$B/mkmodpkg.log" >&2; exit 1; }
        Z=$(tail -1 "$B/mkmodpkg.log")
        [ -f "$Z" ] || { cat "$B/mkmodpkg.log" >&2; echo "no signed package made" >&2; exit 1; }
        cp "$Z" dist/
        echo "dist/$(basename "$Z")"
    done
fi
