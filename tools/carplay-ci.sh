#!/bin/sh
# The CarPlay mod (carplay/) built and tested off-car, as CI runs it:
# carplay/tools/build-release.py (its native library, host tests, HMG-API hook plans,
# signing and the real HMG-API package engine), then carplay/tests/test-release.py.
#
# Releases are signed with the CarPlay mod's own author key (carplay/release.json
# "author_uuid"), which CI doesn't have. So this builds a copy of carplay/ in a temporary
# folder, signed with a throwaway key whose UUID replaces the real one in that copy only
# (release.json, the tests and the NEMESIS scripts pin it). The tree isn't changed and
# nothing built here is for release.
#
#   HMGAPI_SRC=<HMG-API source, the release hmgapi-sdk.json pins>
#   WIRELESS_SRC=<Open-HMG/hmgapi-wireless-cp-aa checkout>
#   ANDROID_JAR=<platforms/android-N/android.jar>  D8_JAR=<build-tools/N/lib/d8.jar>
#   JSON_JAR=<JSON-java (org.json) jar>  JAVA_HOME=<a JDK, with its JNI headers>
#   [CLANG=clang] [LLD=ld.lld] [PYTHON=python3, with pyelftools] [HMGAPI_SDK=<unpacked mod SDK>]
#     sh tools/carplay-ci.sh
set -e
cd "$(dirname "$0")/.."
for v in HMGAPI_SRC WIRELESS_SRC ANDROID_JAR D8_JAR JSON_JAR JAVA_HOME; do
    eval "x=\${$v:-}"
    [ -n "$x" ] || { echo "carplay-ci: set $v (see the top of tools/carplay-ci.sh)" >&2; exit 1; }
done
CLANG=${CLANG:-clang}
LLD=${LLD:-ld.lld}
PYTHON=${PYTHON:-python3}
# the mod SDK's signer, for the throwaway key (hmgapi-mod/sdk.sh fetches the pinned SDK)
[ -n "$HMGAPI_SDK" ] || eval "$(sh hmgapi-mod/sdk.sh)"
"$PYTHON" -c 'import elftools' 2>/dev/null || { echo "carplay-ci: $PYTHON has no pyelftools (pip install pyelftools)" >&2; exit 1; }

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
cp -R carplay "$T/carplay"
rm -rf "$T/carplay/out" "$T/carplay/dist"
SIGN="$JAVA_HOME/bin/java -cp $HMGAPI_SDK/lib/hmgapi-engine.jar hmgapi.engine.Signing"
$SIGN keygen "$T/ci.key" >/dev/null
CI_UUID=$($SIGN uuid "$T/ci.key")
"$PYTHON" - "$T/carplay" "$CI_UUID" <<'PY'
import json, os, sys
root, new = sys.argv[1], sys.argv[2]
old = json.load(open(os.path.join(root, "release.json")))["author_uuid"]
n = 0
for d, _, files in os.walk(root):
    for f in files:
        p = os.path.join(d, f)
        try:
            s = open(p, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        if old in s:
            open(p, "w", encoding="utf-8").write(s.replace(old, new))
            n += 1
print("carplay-ci: author UUID %s -> throwaway %s in %d files of the copy" % (old, new, n))
PY

cd "$T/carplay"
"$PYTHON" tools/build-release.py --hmgapi-source "$HMGAPI_SRC" --wireless-source "$WIRELESS_SRC" \
    --android-jar "$ANDROID_JAR" --d8-jar "$D8_JAR" --json-jar "$JSON_JAR" --key "$T/ci.key" \
    --java-home "$JAVA_HOME" --clang "$CLANG" --lld "$LLD"
"$PYTHON" tests/test-release.py
