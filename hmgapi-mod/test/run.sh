#!/bin/sh
# Host tests for the HMG-API mod (desktop JVM, no car):
#  - egmp's own tests (gpm-ev/test: PrecondTest, SenderRowTest, EvLogTest,
#    ScaleTest) against the mod's classes: the shared logic, and the EV log row
#    and model scaling with the mod's EvCar / EvModelSender in place
#  - ModTest: Precond's HMG-API vehicle (start / route / stop to the relay),
#    sensor 23, EvCar without a hook map
#  - RelayTest: the relay service's requests to HMG-API
#  - recipe-test.sh: tools/mkrecipe.py and NativeCheck on synthetic libraries
#  - check-scripts.sh, offload-test.sh: the offload service script
#  - engine-test.sh: the built mod through HMG-API's own engine (needs
#    sh hmgapi-mod/build.sh first)
#   HMGAPI=<HMG-API checkout> sh hmgapi-mod/test/run.sh
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
HMGAPI=${HMGAPI:-$REPO/../HMG-API}
HMGAPI=$(cd "$HMGAPI" && pwd)
. "$HMGAPI/tools/env.sh"
[ -d "$HMGAPI/out/classes" ] && [ -f "$HMGAPI/out/hmgapi-client.jar" ] || (cd "$HMGAPI" && sh build.sh runtime client >/dev/null)
PKG=com/google/android/projection/protocol
OUT=build/hmgapi-test
rm -rf "$OUT" && mkdir -p "$OUT/classes"

# the shim's Log/SystemClock first, then the mod's classes over the shared ones
javac -nowarn -d "$OUT/classes" -cp "$ANDROID_JAR:$HMGAPI/out/classes:$HMGAPI/out/hmgapi-client.jar" \
    gpm-ev/test/shim/android/util/Log.java gpm-ev/test/shim/android/os/SystemClock.java \
    gpm-ev/src/$PKG/NavProto.java gpm-ev/src/$PKG/PrecondCore.java gpm-ev/src/$PKG/EvRowAgg.java \
    gpm-ev/src/$PKG/EvLogger.java gpm-ev/src/$PKG/EvModelTemplate.java gpm-ev/src/$PKG/EvDriveLog.java \
    hmgapi-mod/src/$PKG/*.java \
    gpm-ev/stubs/com/hkmc/cluster/NavigationCluster.java gpm-ev/stubs/com/hkmc/cluster/NavigationClusterListener.java \
    gpm-ev/test/PrecondTest.java gpm-ev/test/SenderRowTest.java gpm-ev/test/EvLogTest.java gpm-ev/test/ScaleTest.java \
    hmgapi-mod/test/ModTest.java hmgapi-mod/test/NativeCheckTest.java \
    hmgapi-mod/service/src/dev/egmp/aaev/RelayService.java hmgapi-mod/test/RelayTest.java
CP="$REPO/$OUT/classes:$HMGAPI/out/classes:$HMGAPI/out/hmgapi-client.jar:$ANDROID_JAR"
# from gpm-ev/, where egmp's tests find ../tools/evlog_schema.json
for t in PrecondTest SenderRowTest EvLogTest ScaleTest ModTest; do
    rc=0
    (cd gpm-ev && java -cp "$CP" com.google.android.projection.protocol.$t) > "$OUT/$t.log" 2>&1 || rc=$?
    grep -v "not found by name; using" "$OUT/$t.log" || true
    [ $rc -eq 0 ] || { echo "FAIL  $t"; exit 1; }
done
java -cp "$CP" dev.egmp.aaev.RelayTest
CP="$CP" sh hmgapi-mod/test/recipe-test.sh

sh hmgapi-mod/test/check-scripts.sh
sh hmgapi-mod/test/offload-test.sh
HMGAPI="$HMGAPI" sh hmgapi-mod/test/engine-test.sh
