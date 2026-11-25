#!/bin/sh
# Host tests for the HMG-API mod (desktop JVM, no car):
#  - egmp's own tests (gpm-ev/test: PrecondTest, SenderRowTest, EvLogTest,
#    ScaleTest) against the mod's classes: the shared logic, and the EV log row
#    and model scaling with the mod's EvCar / EvModelSender in place
#  - PrecondConfigTest: the preconditioning thresholds from the mod's settings
#  - SocCurveTest: the battery % curve message (sensor 26), as Maps reads it
#  - ModTest: Precond's HMG-API vehicle (start / route / stop to the relay),
#    sensor 23, EvCar without a hook map
#  - RelayTest: the relay service's requests to HMG-API
#  - SitesCheckTest: NativeCheck with the relocatable recipe HMG-API resolved
#  - recipe-test.sh: tools/mkrecipe.py and NativeCheck on synthetic libraries
#  - sites-test.sh: recipes/ev-sensor.sites by HMG-API's SiteFind on a stock library
#    (SITEFIND_CP, EV_STOCK_LIBRARY; skipped without them)
#  - check-scripts.sh, offload-test.sh: the offload service script
#  - engine-test.sh: both built mods through HMG-API's own engine (needs
#    sh hmgapi-mod/build.sh first)
#   [WIRELESS=<the Wireless mod's folder>] sh hmgapi-mod/test/run.sh
# with the HMG-API mod SDK and hook maps hmgapi-mod/sdk.sh fetches (or HMGAPI_SDK, HMGAPI_MAPS)
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -n "$HMGAPI_SDK" ] && [ -n "$HMGAPI_MAPS" ] || eval "$(sh hmgapi-mod/sdk.sh)"
HMGAPI_SDK=$(cd "$HMGAPI_SDK" && pwd)
. "$HMGAPI_SDK/bin/env.sh"
RT=$HMGAPI_SDK/lib/hmgapi-runtime.jar:$HMGAPI_SDK/lib/hmgapi-runtime-stubs.jar
CLIENT=$HMGAPI_SDK/lib/hmgapi-client.jar
PKG=com/google/android/projection/protocol
OUT=build/hmgapi-test
rm -rf "$OUT" && mkdir -p "$OUT/classes"

# the shim's Log/SystemClock first, then the mod's classes over the shared ones
javac -nowarn -d "$OUT/classes" -cp "$ANDROID_JAR:$RT:$CLIENT" \
    gpm-ev/test/shim/android/util/Log.java gpm-ev/test/shim/android/os/SystemClock.java \
    gpm-ev/src/$PKG/NavProto.java gpm-ev/src/$PKG/PrecondCore.java gpm-ev/src/$PKG/EvRowAgg.java \
    gpm-ev/src/$PKG/EvLogger.java gpm-ev/src/$PKG/EvModelTemplate.java gpm-ev/src/$PKG/EvDriveLog.java gpm-ev/src/$PKG/EvUsbFeed.java gpm-ev/src/$PKG/RoadLearn.java \
    gpm-ev/src/$PKG/ChargeLearn.java gpm-ev/src/$PKG/ChargeCurve.java \
    hmgapi-mod/src/$PKG/*.java \
    gpm-ev/stubs/com/hkmc/cluster/NavigationCluster.java gpm-ev/stubs/com/hkmc/cluster/NavigationClusterListener.java \
    gpm-ev/test/PrecondTest.java gpm-ev/test/SenderRowTest.java gpm-ev/test/EvLogTest.java gpm-ev/test/ScaleTest.java gpm-ev/test/RoadLearnTest.java gpm-ev/test/ChargeLearnTest.java \
    hmgapi-mod/test/ModTest.java hmgapi-mod/test/SocCurveTest.java hmgapi-mod/test/WinterTest.java hmgapi-mod/test/NativeCheckTest.java hmgapi-mod/test/SitesCheckTest.java hmgapi-mod/test/PrecondConfigTest.java \
    hmgapi-mod/service/src/dev/egmp/aaev/RelayService.java hmgapi-mod/test/RelayTest.java
CP="$REPO/$OUT/classes:$RT:$CLIENT:$ANDROID_JAR"
# from gpm-ev/, where egmp's tests find ../tools/evlog_schema.json
for t in PrecondTest PrecondConfigTest SenderRowTest EvLogTest ScaleTest RoadLearnTest ChargeLearnTest ModTest SocCurveTest WinterTest; do
    rc=0
    (cd gpm-ev && java -cp "$CP" com.google.android.projection.protocol.$t) > "$OUT/$t.log" 2>&1 || rc=$?
    grep -v "not found by name; using" "$OUT/$t.log" || true
    [ $rc -eq 0 ] || { echo "FAIL  $t"; exit 1; }
done
java -cp "$CP" dev.egmp.aaev.RelayTest
java -cp "$CP" com.google.android.projection.protocol.SitesCheckTest hmgapi-mod/recipes/ev-sensor.sites
CP="$CP" sh hmgapi-mod/test/recipe-test.sh
sh hmgapi-mod/test/sites-test.sh

HMGAPI_SDK="$HMGAPI_SDK" sh hmgapi-mod/test/check-scripts.sh
sh hmgapi-mod/test/offload-test.sh
HMGAPI_SDK="$HMGAPI_SDK" HMGAPI_MAPS="$HMGAPI_MAPS" sh hmgapi-mod/test/engine-test.sh
