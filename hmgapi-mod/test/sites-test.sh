#!/bin/sh
# recipes/ev-sensor.sites resolved by HMG-API's own SiteFind (hmgapi.engine.SiteFind, HMG-API
# after 1.1.0-rc.2) on a stock Android Auto library, and compared with the fixed recipe made
# for that build: the same patch offsets, the same bytes (but for the sensor type limit:
# 22, types up to 23, where the US recipe writes 25). Needs files that aren't in the repo:
#   SITEFIND_CP=<classes with hmgapi.engine.SiteFind>   (HMG-API's tools/engine-classes.sh)
#   EV_STOCK_LIBRARY=<libautoreceiver_infobank.so>       (a stock copy, or one with the Wireless
#                                                         mod's / NEMESIS projection.tbt change)
# Skipped without them. Prints offsets and md5s only, never bytes of the library.
set -e
cd "$(dirname "$0")/../.."
if [ -z "$SITEFIND_CP" ] || [ -z "$EV_STOCK_LIBRARY" ]; then
    echo "skip  sites-test.sh (needs SITEFIND_CP and EV_STOCK_LIBRARY)"
    exit 0
fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
cat > "$T/Resolve.java" <<'J'
public class Resolve {
    public static void main(String[] a) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[0])), "UTF-8");
        byte[] lib = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[1]));
        hmgapi.engine.SiteFind.Resolved r = hmgapi.engine.SiteFind.resolve(text, lib);
        System.out.print(r.recipe != null ? r.recipe : "NOT RESOLVED: " + r.why + "\n");
    }
}
J
javac -nowarn -cp "$SITEFIND_CP" -d "$T" "$T/Resolve.java"
java -cp "$SITEFIND_CP:$T" Resolve hmgapi-mod/recipes/ev-sensor.sites "$EV_STOCK_LIBRARY" > "$T/resolved.recipe"
fail=0
if grep -q '^NOT RESOLVED' "$T/resolved.recipe"; then
    echo "FAIL  ev-sensor.sites: $(cat "$T/resolved.recipe")"; exit 1
fi
md5=$(md5sum "$EV_STOCK_LIBRARY" 2>/dev/null | cut -c1-8 || md5 -q "$EV_STOCK_LIBRARY" | cut -c1-8)
fixed=""
for f in hmgapi-mod/recipes/ev-sensor-*.recipe; do
    # the fixed recipe whose patch offsets these are
    if [ "$(grep '^patch' "$f" | awk '{print $2}')" = "$(grep '^patch' "$T/resolved.recipe" | awk '{print $2}')" ]; then fixed=$f; fi
done
if [ -z "$fixed" ]; then
    echo "FAIL  ev-sensor.sites resolved on $md5... at offsets no fixed recipe has:"; grep '^patch' "$T/resolved.recipe"; exit 1
fi
want=$(grep '^patch' "$fixed" | sed 's/ 1928$/ 1628/')
if [ "$want" = "$(grep '^patch' "$T/resolved.recipe")" ]; then
    echo "ok    ev-sensor.sites resolves on $md5... to ${fixed##*/}'s patches (sensor types up to 23)"
else
    echo "FAIL  ev-sensor.sites on $md5...: wanted"; echo "$want"; echo "got"; grep '^patch' "$T/resolved.recipe"; fail=1
fi
# the same library with a wildcard byte changed (a call offset, as on another build): still
# found; with a compared byte changed (SensorType_IsValid's stock limit): nothing written
off() { grep "^patch" "$T/resolved.recipe" | sed -n "$1p" | awk '{print $2}'; }
python3 -I - "$EV_STOCK_LIBRARY" "$T" "$(off 2)" "$(off 1)" <<'PY'
import sys
lib, t, req, valid = sys.argv[1], sys.argv[2], int(sys.argv[3], 0), int(sys.argv[4], 0)
b = bytearray(open(lib, "rb").read())
w = bytearray(b); w[req + 8 + 1] ^= 0x01        # inside the bl after the patch: a wildcard
open(t + "/wild.so", "wb").write(w)
c = bytearray(b); c[valid] ^= 0x01              # cmp r0,#20 -> #21: compared
open(t + "/cmp.so", "wb").write(c)
PY
r=$(java -cp "$SITEFIND_CP:$T" Resolve hmgapi-mod/recipes/ev-sensor.sites "$T/wild.so")
case "$r" in NOT*) echo "FAIL  a changed call offset (a wildcard) stopped it: $r"; fail=1 ;; *) echo "ok    a changed call offset (a wildcard): still found" ;; esac
r=$(java -cp "$SITEFIND_CP:$T" Resolve hmgapi-mod/recipes/ev-sensor.sites "$T/cmp.so")
case "$r" in NOT*) echo "ok    another SensorType_IsValid limit: not resolved, nothing written" ;; *) echo "FAIL  a changed compared byte still resolved"; fail=1 ;; esac
exit $fail
