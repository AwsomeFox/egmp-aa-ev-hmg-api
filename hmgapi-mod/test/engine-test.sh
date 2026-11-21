#!/bin/sh
# The built mod (build/mod/egmp-aa-ev, from hmgapi-mod/build.sh) through HMG-API's
# own engine on a desktop JVM, on fake US Ioniq 5 units (260507 and 260903) and
# every other E-GMP unit (EV6, Canadian cars, Ioniq 6) whose map the HMG-API checkout has. The
# test uses HMG-API's real hook maps: the mod.json is valid, every hook point it
# declares is mapped and planned, its services, command, USB folder and Android
# Auto settings are taken, and each library recipe parses and stays clear of
# the Wireless mod's recipe for the same library build.
# Both builds (hmgapi-mod/variants.py): egmp-aa-ev, beside the Wireless mod it requires, and
# egmp-aa-ev-standalone, alone (no Wireless, no preconditioning). Then the two together:
# HMG-API refuses that (their "conflicts").
#   HMGAPI_SDK=<unpacked mod SDK> HMGAPI_MAPS=<HMG-API's maps/> WIRELESS=<the Wireless mod's folder> \
#     sh hmgapi-mod/test/engine-test.sh
# (hmgapi-mod/sdk.sh sets the first two; WIRELESS defaults to ../hmgapi-wireless-cp-aa/wireless-carplay-aa)
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -f "${HMGAPI_SDK:-/nonexistent}/lib/hmgapi-engine.jar" ] || { echo "no mod SDK (HMGAPI_SDK; eval \"\$(sh hmgapi-mod/sdk.sh)\")"; exit 1; }
[ -d "${HMGAPI_MAPS:-/nonexistent}" ] || { echo "no hook maps (HMGAPI_MAPS: HMG-API's maps/ at the SDK's commit; hmgapi-mod/sdk.sh)"; exit 1; }
WIRELESS=${WIRELESS:-$REPO/../hmgapi-wireless-cp-aa/wireless-carplay-aa}
[ -f "$WIRELESS/mod.json" ] || { echo "no Wireless mod at $WIRELESS (set WIRELESS to its folder in Open-HMG/hmgapi-wireless-cp-aa)"; exit 1; }
WIRELESS=$(cd "$WIRELESS" && pwd)
FULL=$REPO/build/mod/egmp-aa-ev
SOLO=$REPO/build/mod/egmp-aa-ev-standalone
[ -f "$FULL/hooks.jar" ] && [ -f "$SOLO/hooks.jar" ] || { echo "build the mods first: sh hmgapi-mod/build.sh"; exit 1; }
CP=$HMGAPI_SDK/lib/hmgapi-engine.jar
T=$(mktemp -d); D=$(mktemp -d)
trap 'rm -rf "$T" "$D"' EXIT
fail=0
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in:"; echo "$3" | sed 's/^/        /'; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in:"; echo "$3" | sed 's/^/        /'; fail=1 ;; *) echo "ok    $1" ;; esac; }

unit() {   # unit <ro.build.product> <mod folder>...: a fake head unit with the maps and those mods
    R=$T/$1; rm -rf "$R"; P1=$1; shift
    mkdir -p "$R/system/lib" "$R/system/app" "$R/data/hmgapi/mods" "$R/data/hmgapi/settings"
    printf 'ro.build.product=%s\nro.build.version.release=4.4.2\n' "$P1" > "$R/system/build.prop"
    cp -r "$HMGAPI_MAPS" "$R/data/hmgapi/maps"
    cp "$HMGAPI_SDK/points.txt" "$R/data/hmgapi/maps/points.txt"
    for m in "$@"; do
        cp -r "$m" "$R/data/hmgapi/mods/$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$m/mod.json")"
    done
    # Testing maps lets this off-car fixture exercise maps that are not marked
    # verified yet (the CEEV 260903 map itself is car-verified).
    mkdir -p "$R/data/hmgapi/settings/allow" && touch "$R/data/hmgapi/settings/allow/testing_maps"
    # Each map is tied to the md5s of the stock files it was made from. The fake
    # unit gets a stand-in for each, and its copy of the maps those stand-ins'
    # md5s, so the engine runs with the real point names (no car files here).
    python3 - "$R" <<'PY'
import glob, hashlib, json, os, sys
R = sys.argv[1]
for cj in glob.glob(R + "/data/hmgapi/maps/*/*/*/*/car.json"):
    car = json.load(open(cj))
    for key in ("files", "app_files"):
        files = car.get(key)
        if not isinstance(files, dict):
            continue
        for path in files:
            f = R + path
            os.makedirs(os.path.dirname(f), exist_ok=True)
            if not os.path.exists(f):
                open(f, "wb").write(("stand-in " + path).encode())
            files[path] = hashlib.md5(open(f, "rb").read()).hexdigest()
    json.dump(car, open(cj, "w"), indent=2)
PY
}
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=$P1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

PRODUCTS="NEEV23.USA.0000.V015.012.260507 NEEV.USA.0000.V015.012.260507 NEEV23.USA.0000.V016.010.260903"
# and every other E-GMP car whose map the SDK's HMG-API has
for e in "kia/ev6/usa/260507 CVEV.USA.0000.V015.012.260507" "kia/ev6/can/260206 CVEV.CAN.0000.V015.010.260206" \
         "hyundai/neev/can/260206 NEEV.CAN.0000.V015.010.260206" "hyundai/ceev/usa/260903 CEEV.USA.0000.V016.010.260903"; do
    set -- $e
    [ -f "$HMGAPI_MAPS/$1/car.json" ] && PRODUCTS="$PRODUCTS $2"
done
# an EV that isn't E-GMP (Niro EV): the standalone build runs on it; the full one only where
# the Wireless mod it requires does, which is the Wireless mod's to say
OTHER=""
[ -f "$HMGAPI_MAPS/kia/sg2ev/usa/260507/car.json" ] && OTHER="SG2EV.USA.0000.V015.012.260507"

cat > "$D/R.java" <<'J'
import hmgapi.engine.MemRecipe;
import java.nio.file.*;
public class R {
    public static void main(String[] a) throws Exception {
        MemRecipe ours = MemRecipe.parse(new String(Files.readAllBytes(Paths.get(a[0]))));
        System.out.println("recipe " + ours.id + ": " + ours.verifies.size() + " verify, " + ours.patchAt.size() + " patch");
        for (int i = 1; i < a.length; i++) {
            MemRecipe o = MemRecipe.parse(new String(Files.readAllBytes(Paths.get(a[i]))));
            for (long[] x : ours.regions()) for (long[] y : o.regions())
                if (x[0] < y[1] && y[0] < x[1]) { System.out.println("OVERLAP with " + o.id + " at 0x" + Long.toHexString(x[0])); System.exit(1); }
            System.out.println("clear of " + o.id);
        }
    }
}
J
cat > "$D/C.java" <<'J'
import hmgapi.engine.MemRecipe;
import java.nio.file.*;
import java.util.*;
/** C <combined> <part>...: the combined recipe's regions are its parts' (that exist), none twice */
public class C {
    public static void main(String[] a) throws Exception {
        MemRecipe all = MemRecipe.parse(new String(Files.readAllBytes(Paths.get(a[0]))));
        List<String> want = new ArrayList<String>(), got = new ArrayList<String>();
        for (long[] x : all.regions()) got.add(x[0] + "-" + x[1]);
        for (int i = 1; i < a.length; i++) {
            if (!Files.exists(Paths.get(a[i]))) continue;
            for (long[] x : MemRecipe.parse(new String(Files.readAllBytes(Paths.get(a[i])))).regions()) want.add(x[0] + "-" + x[1]);
        }
        long[][] r = all.regions().toArray(new long[0][]);
        for (int i = 0; i < r.length; i++) for (int j = i + 1; j < r.length; j++)
            if (r[i][0] < r[j][1] && r[j][0] < r[i][1]) { System.out.println("OVERLAP inside " + all.id + " at 0x" + Long.toHexString(r[i][0])); System.exit(1); }
        Collections.sort(want); Collections.sort(got);
        System.out.println(want.equals(got) ? all.id + ": same regions (" + got.size() + ")" : all.id + ": regions differ " + got + " vs " + want);
        System.exit(want.equals(got) ? 0 : 1);
    }
}
J
javac -cp "$CP" -d "$D" "$D/R.java" "$D/C.java"

# check_mod <mod folder> <full|standalone>
check_mod() {
    MOD=$1; KIND=$2
    ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$MOD/mod.json")
    echo "=== $ID"
    if [ $KIND = full ]; then
        for P in $OTHER; do
            unit $P "$MOD" "$WIRELESS"
            eng plan
            hasnt "$P: no mod.json errors, matches the car" "$ID: " "$(echo "$out" | grep -i 'error\|isn.t one\|must be\|needs \|matches none' || true)"
            r=$(echo "$out" | grep "$ID won't load" || true)
            case "$r" in
                "") echo "ok    $P: hooks load (the Wireless mod runs here)" ;;
                *"requires wireless-carplay-aa, which isn't loaded"*) echo "ok    $P: waits only for the Wireless mod, which doesn't run here (the standalone build does)" ;;
                *) echo "FAIL  $P: won't load for its own reason: $r"; fail=1 ;;
            esac
        done
        PLAN=$PRODUCTS
    else
        PLAN="$PRODUCTS $OTHER"
    fi
    for P in $PLAN; do
        if [ $KIND = full ]; then unit $P "$MOD" "$WIRELESS"; else unit $P "$MOD"; fi
        eng plan
        has "$P: plan runs" "$ID" "$out"
        hasnt "$P: no mod.json errors, matches the car" "$ID: " "$(echo "$out" | grep -i 'error\|isn.t one\|must be\|needs \|matches none' || true)"
        # (the conditioning command's own description says when a start is refused)
        hasnt "$P: hooks not refused" "refused" "$(echo "$out" | grep -v '^vehicle ' || true)"
        hasnt "$P: hooks load" "won't load" "$out"
        has "$P: offload service" "offload" "$out"
        if [ $KIND = full ]; then
            has "$P: shares GPM's next-turn callback with the Wireless mod" "gpm.navigation_status.next_turn: " "$out"
        else
            hasnt "$P: no Wireless mod needed" "wireless-carplay-aa" "$out"
        fi
        # both builds precondition: the standalone one with its own route data patch
        has "$P: conditioning command" "conditioning" "$out"
        has "$P: relay service" "relay" "$out"
        echo "$out" > "$T/plan-$ID-$P.txt"
    done
    # which cars the mod is for (mod.json "system"), by HMG-API's own check: every EV
    # model HMG-API can map (E-GMP, and Kona Electric, Niro EV, Soul EV, Ioniq Electric),
    # any region and firmware. Whether it
    # runs is then up to HMG-API's map for that unit (hooks off without one), the library
    # recipe for its Android Auto library build (no energy model without one), and the
    # mod's own EV check (EvCar.notAnEvWeFit). Any other model, gas cars included, isn't
    for P in NEEV23.USA.0000.V017.001.261201 NEEV23SE.USA.0000.V015.012.260507 CEEV.USA.0000.V016.010.260903 \
             CEEV.USA.0000.V017.001.261201 NEEV23.USA.0000.V014.005.250101 CVEV.USA.0000.V015.012.260507 \
             CVEV.CAN.0000.V015.010.260206 NEEV.CAN.0000.V015.010.260206 OSEV.USA.0000.V015.012.260507 \
             SG2EV.USA.0000.V015.012.260507 DE22EV.CAN.0000.V015.010.260206 SK3EV22.USA.0000.V015.012.260507; do
        has "$P: for this car" "ok: $ID supports this unit" "$(sys $P)"
    done
    has "a Kia K5 (gas): not for this car" "needs model" "$(sys DL3.USA.0000.V015.012.260507)"
    has "a K5 Hybrid: not for this car" "needs model" "$(sys DL3HEV.USA.0000.V015.012.260507)"
    has "a Niro Hybrid: not for this car" "needs model" "$(sys SG2HEV.USA.0000.V015.012.260507)"
    has "a Niro Plug-in Hybrid: not for this car" "needs model" "$(sys SG2PHEV.USA.0000.V015.012.260507)"
    P=NEEV23.USA.0000.V017.001.261201
    if [ $KIND = full ]; then unit $P "$MOD" "$WIRELESS"; else unit $P "$MOD"; fi
    eng plan
    has "$P: no map yet, so its hooks are off" "$ID won't load" "$out"

    # every hook point the mod declares is in each car's map (the "all hooks are there" check)
    for m in $(for c in "$HMGAPI_MAPS"/*/*/*/*/car.json; do dirname "$c"; done); do
    r=$(python3 - "$MOD/mod.json" "$m" <<'PY'
import glob, json, sys
mod = json.load(open(sys.argv[1]))
have = {}
for f in glob.glob(sys.argv[2] + "/*.json"):
    if not f.endswith("/car.json"):
        have.update(json.load(open(f)))
missing = json.load(open(sys.argv[2] + "/car.json")).get("missing", {})
want = list(mod["hooks"]["points"]) + [
    # read or called by name through Points (HmgCar, HmgApiEntry)
    "framework.car_services.hev", "framework.car_services.bcm", "framework.car_services.datc",
    "framework.hscan_adapter.service_name", "framework.hev_service_stub.as_interface",
    "framework.hscan_service_stub.as_interface", "framework.datc_service_stub.as_interface",
    "framework.bcm_service_stub.as_interface", "framework.hev_service.hev_status", "framework.hev_flag",
    "framework.hev_flag.battery_conditioning", "framework.hev_flag.ev_ready",
    "framework.hscan_service.car_speed", "framework.hscan_service.hv_health",
    "framework.bcm_service.register_listener", "framework.bcm_listener", "framework.bcm_listener_stub",
    "framework.bcm_listener_stub.as_interface", "framework.runtime.load_library", "framework.runtime.load"]
gone = [p for p in want if p not in have or p in missing]
print("missing: " + " ".join(gone) if gone else "all %d mapped" % len(want))
PY
)
        has "${m#$HMGAPI_MAPS/}: every point the mod uses is mapped" "all " "$r"
    done

    # the recipes: parse, and no region overlaps the Wireless mod's recipe for that library build
    for ours in "$MOD"/recipes/ev-sensor-*.recipe; do
        case "$ours" in */ev-sensor-route-*) continue ;; esac
        suffix=${ours##*/ev-sensor-}; suffix=${suffix%.recipe}
        wireless="$WIRELESS/recipes/aa-tbt-$suffix.recipe"
        set +e
        if [ -f "$wireless" ]; then r=$(java -cp "$CP:$D" R "$ours" "$wireless" 2>&1); rc=$?
        else r=$(java -cp "$CP:$D" R "$ours" 2>&1); rc=$?
        fi
        set -e
        # 4 verify / 3 patch, plus one of each where the recipe also widens the connector types (NACS)
        nv=$(grep -c '^verify' "$ours"); np=$(grep -c '^patch' "$ours")
        has "recipe ev-sensor-$suffix parses ($nv verify, $np patch)" "$nv verify, $np patch" "$r"
        [ -f "$wireless" ] && has "recipe ev-sensor-$suffix clear of the matching Wireless recipe" "clear of aa-tbt-$suffix" "$r"
        [ $rc -eq 0 ] || { echo "FAIL  recipe check: $r"; fail=1; }
    done
    # the standalone build's: the energy model's and the Wireless mod's route data in one, each
    # as the Wireless mod has it (so its regions are the Wireless recipe's, and ours clear of them)
    for ours in "$MOD"/recipes/ev-sensor-route-*.recipe "$MOD"/recipes/route-*.recipe; do
        [ -f "$ours" ] || continue
        [ $KIND = standalone ] || { echo "FAIL  ${ours##*/} in the full build"; fail=1; }
        name=${ours##*/}; name=${name%.recipe}; md5=${name##*-}
        set +e; r=$(java -cp "$CP:$D" C "$ours" "hmgapi-mod/route/aa-tbt-$md5.recipe" "hmgapi-mod/recipes/ev-sensor-$md5.recipe" 2>&1); rc=$?; set -e
        has "recipe $name: the Wireless mod's route data recipe plus ours, no region twice" "same regions" "$r"
        [ $rc -eq 0 ] || { echo "FAIL  recipe check: $r"; fail=1; }
        has "$name listed in mod.json before the energy model's own" "recipes/$name.recipe" \
            "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["hooks"]["native"]["libautoreceiver_infobank.so"][0:3])' "$MOD/mod.json")"
    done
}
sys() { HMGAPI_ROOT=$T/none HMGAPI_PRODUCT=$1 java -cp "$CP" hmgapi.engine.SystemCheck "$MOD" 2>&1 || true; }

check_mod "$FULL" full
check_mod "$SOLO" standalone

echo "=== both"
P=NEEV23.USA.0000.V015.012.260507
unit $P "$FULL" "$SOLO" "$WIRELESS"
eng plan
# HMG-API (1.1.0) checks "conflicts" only for mods that change files, so this plan
# doesn't refuse it yet: HmgApiEntry.standDown keeps the standalone build off instead
# (ModTest). Both declare the other, so it's refused here once HMG-API checks it.
has "both builds declare the other in conflicts" "egmp-aa-ev egmp-aa-ev-standalone" "$(python3 -c 'import json,sys; print(" ".join(sorted(c for f in sys.argv[1:] for c in json.load(open(f))["conflicts"])))' "$FULL/mod.json" "$SOLO/mod.json")"
unit $P "$SOLO" "$WIRELESS"
eng plan
# (the fake unit has no Wi-Fi chip, so the Wireless mod itself reports it doesn't fit: not a conflict)
hasnt "standalone beside the Wireless mod: no conflict" "conflict" "$out"

echo "--- plan on NEEV23 260507:"; sed 's/^/    /' "$T/plan-egmp-aa-ev-NEEV23.USA.0000.V015.012.260507.txt"
exit $fail
