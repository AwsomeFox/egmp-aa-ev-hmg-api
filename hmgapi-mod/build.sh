#!/bin/sh
# Build the egmp-aa-ev HMG-API mod into build/mod/egmp-aa-ev/ (and, with a key,
# the signed package dist/egmp-aa-ev-<version>.zip).
#
#   sh hmgapi-mod/build.sh                 the mod folder (unsigned)
#   sh hmgapi-mod/build.sh <author key>    plus the signed package, through
#                                          HMG-API's tools/mkmodpkg.sh
#
# Needs an HMG-API checkout (HMGAPI, default ../HMG-API next to this repo; its
# runtime and client are built here, which needs only a JDK and the Android
# SDK, not clang), a JDK and the Android SDK (build-tools, a platform
# android.jar; ANDROID_HOME). A new author key:
#   java -cp "$HMGAPI/out/engine-host" hmgapi.engine.Signing keygen <key file>
#
# hooks.jar: the mod's code in GPM. The classes the NEMESIS build shares
# (gpm-ev/src: NavProto, PrecondCore, EvRowAgg, EvLogger, EvModelTemplate,
# EvDriveLog) come from there unchanged, except EvDriveLog's package line in
# the log header; EvCar, EvModelSender and Precond are this mod's own
# (hmgapi-mod/src), with HmgApiEntry, HmgCar, NativeCheck and Relay.
# relay.jar: the relay service, with hmgapi-client.jar's classes inside.
set -e
cd "$(dirname "$0")/.."
REPO=$PWD
HMGAPI=${HMGAPI:-$REPO/../HMG-API}
[ -f "$HMGAPI/tools/env.sh" ] || { echo "no HMG-API checkout at $HMGAPI (set HMGAPI)" >&2; exit 1; }
HMGAPI=$(cd "$HMGAPI" && pwd)
. "$HMGAPI/tools/env.sh"
[ -x "$D8" ] && [ -f "$ANDROID_JAR" ] || { echo "Android SDK not found (ANDROID_HOME)" >&2; exit 1; }

ID=egmp-aa-ev
VER=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' hmgapi-mod/mod.json | head -1)
B=build/hmgapi
MOD=build/mod/$ID
PKG=com/google/android/projection/protocol
rm -rf "$B" "$MOD"
mkdir -p "$B/src/$PKG" "$B/hooks" "$B/relay" "$MOD"

echo "== HMG-API runtime and client ($HMGAPI)"
(cd "$HMGAPI" && sh build.sh runtime client >/dev/null)

echo "== hooks.jar"
for c in NavProto PrecondCore EvRowAgg EvLogger EvModelTemplate EvDriveLog; do
    cp "gpm-ev/src/$PKG/$c.java" "$B/src/$PKG/"
done
# the log header names the writer
sed -i.orig 's/static final String PKG = "aa.ev.model [^"]*";/static final String PKG = "egmp-aa-ev '"$VER"' (HMG-API)";/' "$B/src/$PKG/EvDriveLog.java"
grep -q "egmp-aa-ev $VER (HMG-API)" "$B/src/$PKG/EvDriveLog.java" || { echo "EvDriveLog PKG line not found" >&2; exit 1; }
rm -f "$B/src/$PKG/EvDriveLog.java.orig"
cp hmgapi-mod/src/$PKG/*.java "$B/src/$PKG/"
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$HMGAPI/out/classes" -d "$B/hooks" \
    $(find "$B/src" -name '*.java') \
    gpm-ev/stubs/com/hkmc/cluster/NavigationCluster.java gpm-ev/stubs/com/hkmc/cluster/NavigationClusterListener.java
# our classes only: never the car's (stubs) or HMG-API's
"$D8" --release --min-api 19 --lib "$ANDROID_JAR" --classpath "$HMGAPI/out/classes" \
    --classpath "$B/hooks" --output "$MOD/hooks.jar" $(find "$B/hooks/$PKG" -name '*.class')
for c in $(cd "$B/hooks" && find "$PKG" -name '*.class' | sed 's/\.class$//' | sort); do
    unzip -p "$MOD/hooks.jar" classes.dex | grep -q "L$c;" || { echo "class not in hooks.jar: $c" >&2; exit 1; }
done

echo "== relay.jar"
mkdir -p "$B/relay-classes"
(cd "$B/relay-classes" && jar xf "$HMGAPI/out/hmgapi-client.jar" hmgapi)
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$HMGAPI/out/hmgapi-client.jar" -d "$B/relay-classes" \
    $(find hmgapi-mod/service/src -name '*.java')
"$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$MOD/relay.jar" $(find "$B/relay-classes" -name '*.class')

echo "== mod folder"
cp hmgapi-mod/mod.json hmgapi-mod/settings.json "$MOD/"
mkdir -p "$MOD/recipes" "$MOD/scripts"
cp hmgapi-mod/recipes/*.recipe "$MOD/recipes/"
cp hmgapi-mod/scripts/offload.sh "$MOD/scripts/"
chmod 755 "$MOD/scripts/offload.sh"
if grep -l "$(printf '\r')" "$MOD/scripts/offload.sh" "$MOD"/*.json >/dev/null 2>&1; then
    echo "CRLF line endings in the mod (the head unit's shell can't run them)" >&2
    exit 1
fi
(cd "$MOD" && find . -type f | sort | xargs md5sum 2>/dev/null || (cd "$MOD" && find . -type f | sort | xargs md5 -r))

if [ -n "$1" ]; then
    echo "== signed package"
    # HMG-API's packer signs with <key> and the <key>.pub beside it, then verifies
    sh "$HMGAPI/tools/mkmodpkg.sh" "$REPO/$MOD" "$1" > "$B/mkmodpkg.log" 2>&1 || { cat "$B/mkmodpkg.log" >&2; exit 1; }
    Z=$(tail -1 "$B/mkmodpkg.log")
    [ -f "$Z" ] || { cat "$B/mkmodpkg.log" >&2; echo "no signed package made" >&2; exit 1; }
    mkdir -p dist
    cp "$Z" "dist/$ID-$VER.zip"
    echo "dist/$ID-$VER.zip"
fi
