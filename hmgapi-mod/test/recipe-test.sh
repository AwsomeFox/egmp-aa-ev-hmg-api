#!/bin/sh
# tools/mkrecipe.py and NativeCheck on synthetic libraries (no car files): the
# US 260507 recipe's patch lines come out of the tool unchanged; a recipe for a
# build with the sites elsewhere (--sites/--queue) is refused at the wrong
# offsets and made at the right ones; NativeCheck, reading the recipes as the
# zygote does, finds the recipe whose patches are in memory, and none in an
# unpatched library.  Needs the classes from run.sh (CP).
set -e
cd "$(dirname "$0")/../.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
python3 -I - "$T" <<'PY'
import os, random, struct, sys
T = sys.argv[1]
random.seed(23)
def lib(sites, queue):
    b = bytearray(random.getrandbits(8) for _ in range(0x150000))
    for off, old in zip(sites, (b"\x14\x28", bytes.fromhex("6ff00807"), bytes.fromhex("2de9f041"))):
        b[off:off + len(old)] = old
    return b
open(T + "/us.so", "wb").write(lib((0x144EC2, 0xBB29E, 0xBB638), 0xB1972))
open(T + "/other.so", "wb").write(lib((0x145002, 0xBB3A2, 0xBB73C), 0xB1A70))
PY
fail=0
ok() { echo "ok    $1"; }
bad() { echo "FAIL  $1"; fail=1; }
M=hmgapi-mod/tools/mkrecipe.py
mkdir -p "$T/recipes"
python3 -I $M "$T/us.so" "$T/recipes/ev-sensor-48ee793a.recipe" >/dev/null
[ "$(grep '^patch' "$T/recipes/ev-sensor-48ee793a.recipe")" = "$(grep '^patch' hmgapi-mod/recipes/ev-sensor-48ee793a.recipe)" ] \
    && ok "US 260507: the shipped recipe's patch lines" || bad "US 260507 patch lines differ from the shipped recipe"
if python3 -I $M "$T/other.so" "$T/x.recipe" >/dev/null 2>&1; then bad "another build at the US offsets was accepted"; else ok "another build at the US offsets: refused"; fi
if python3 -I $M "$T/other.so" "$T/x.recipe" --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a70 >/dev/null 2>&1; then bad "--sites without --id accepted"; else ok "--sites needs its own --id"; fi
python3 -I $M "$T/other.so" "$T/recipes/ev-sensor-0ther000.recipe" --id ev-sensor-0ther000 \
    --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a70 --avoid hmgapi-mod/recipes/ev-sensor-48ee793a.recipe >/dev/null \
    && ok "another build: recipe made at its own offsets" || bad "another build's recipe not made"
if python3 -I $M "$T/other.so" "$T/y.recipe" --id ev-sensor-y --sites 0x145002,0xbb3a2,0xbb73c --queue 0xb1a70 \
        --avoid "$T/recipes/ev-sensor-0ther000.recipe" >/dev/null 2>&1; then bad "overlap with --avoid accepted"; else ok "overlapping an --avoid recipe: refused"; fi
# the "memory": each library with its recipe's patch lines applied
python3 -I - "$T" <<'PY'
import sys
T = sys.argv[1]
for so, rec in (("us", "ev-sensor-48ee793a"), ("other", "ev-sensor-0ther000")):
    b = bytearray(open("%s/%s.so" % (T, so), "rb").read())
    for l in open("%s/recipes/%s.recipe" % (T, rec)):
        w = l.split()
        if w and w[0] == "patch":
            p = bytes.fromhex(w[2]); o = int(w[1], 0); b[o:o + len(p)] = p
    open("%s/%s.patched" % (T, so), "wb").write(b)
PY
J="java -cp $CP com.google.android.projection.protocol.NativeCheckTest $T/recipes"
$J "$T/us.patched" ev-sensor-48ee793a || fail=1
$J "$T/other.patched" ev-sensor-0ther000 || fail=1
$J "$T/us.so" none || fail=1
$J "$T/other.so" none || fail=1
exit $fail
