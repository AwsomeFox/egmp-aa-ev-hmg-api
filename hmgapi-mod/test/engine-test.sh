#!/bin/sh
# The built mod (build/mod/egmp-aa-ev, from hmgapi-mod/build.sh) through HMG-API's
# own engine on a desktop JVM, on a fake head unit that says it is a US Ioniq 5
# on 260507 (then 260903) and has HMG-API's real hook maps: the mod.json is
# valid, every hook point it declares is mapped and planned, its services,
# command, USB folder and Android Auto settings are taken, and its library
# recipe parses and stays clear of the Wireless mod's.
#   HMGAPI=<HMG-API checkout> sh hmgapi-mod/test/engine-test.sh
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
HMGAPI=${HMGAPI:-$REPO/../HMG-API}
HMGAPI=$(cd "$HMGAPI" && pwd)
MOD=$REPO/build/mod/egmp-aa-ev
[ -f "$MOD/hooks.jar" ] || { echo "build the mod first: sh hmgapi-mod/build.sh"; exit 1; }
CP=$(mktemp -d); T=$(mktemp -d)
trap 'rm -rf "$CP" "$T"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" "$HMGAPI"/core/runtime/java/hmgapi/engine/*.java
fail=0
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in:"; echo "$3" | sed 's/^/        /'; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in:"; echo "$3" | sed 's/^/        /'; fail=1 ;; *) echo "ok    $1" ;; esac; }

unit() {   # unit <ro.build.product>: a fake head unit with the maps and the mod
    R=$T/$1; rm -rf "$R"
    mkdir -p "$R/system/lib" "$R/system/app" "$R/data/hmgapi/mods" "$R/data/hmgapi/settings"
    printf 'ro.build.product=%s\nro.build.version.release=4.4.2\n' "$1" > "$R/system/build.prop"
    cp -r "$HMGAPI/maps" "$R/data/hmgapi/maps"
    python3 -c 'import glob,json,sys; [print(p["id"]) for f in sorted(glob.glob(sys.argv[1]+"/core/hookpoints/*.json")) for p in json.load(open(f))["points"]]' "$HMGAPI" > "$R/data/hmgapi/maps/points.txt"
    cp -r "$MOD" "$R/data/hmgapi/mods/egmp-aa-ev"
    # next to HMG-API's own Wireless CarPlay + Android Auto mod, as on a car
    cp -r "$HMGAPI/mods/wireless-carplay-aa" "$R/data/hmgapi/mods/wireless-carplay-aa"
    # the Ioniq 5 maps aren't checked on a car yet: an owner turns on "testing
    # maps" (or runs hmgapid engine verify-map) before hooks run there
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
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=$P java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

for P in NEEV23.USA.0000.V015.012.260507 NEEV.USA.0000.V015.012.260507 NEEV23.USA.0000.V016.010.260903; do
    unit $P
    eng plan
    has "$P: plan runs" "egmp-aa-ev" "$out"
    hasnt "$P: no mod.json errors, matches the car" "egmp-aa-ev: " "$(echo "$out" | grep -i 'error\|isn.t one\|must be\|needs \|matches none' || true)"
    # (the conditioning command's own description, HMG-API 1.0.0, says when a start is refused)
    hasnt "$P: hooks not refused" "refused" "$(echo "$out" | grep -v '^vehicle ' || true)"
    hasnt "$P: hooks load" "won't load" "$out"
    has "$P: shares GPM's next-turn callback with the Wireless mod" "gpm.navigation_status.next_turn: " "$out"
    has "$P: conditioning command" "conditioning" "$out"
    has "$P: relay service" "relay" "$out"
    has "$P: offload service" "offload" "$out"
    echo "$out" > "$T/plan-$P.txt"
done
# which cars the mod is for (mod.json "system"), by HMG-API's own check: a firmware
# newer than any map still matches (its hooks are just off until HMG-API has that
# map); an older one, or another model, doesn't
sys() { HMGAPI_ROOT=$T/none HMGAPI_PRODUCT=$1 java -cp "$CP" hmgapi.engine.SystemCheck "$MOD" 2>&1 || true; }
has "a newer firmware (261201): for this car" "ok: egmp-aa-ev supports this unit" "$(sys NEEV23.USA.0000.V017.001.261201)"
has "NEEV23SE 260507: for this car" "ok: egmp-aa-ev supports this unit" "$(sys NEEV23SE.USA.0000.V015.012.260507)"
has "older than 260507: not for this car" "needs firmware >=260507 (this unit: 250101)" "$(sys NEEV23.USA.0000.V014.005.250101)"
has "an EV6: not for this car (its energy model is the Ioniq 5's)" "needs model" "$(sys CVEV.USA.0000.V015.012.260507)"
has "Canada: not for this car" "needs region USA" "$(sys NEEV.CAN.0000.V015.010.260206)"
P=NEEV23.USA.0000.V017.001.261201
unit $P
eng plan
has "$P: no map yet, so its hooks are off" "egmp-aa-ev won't load" "$out"

# every hook point the mod declares is in each car's map (the "all hooks are there" check)
for m in "$HMGAPI"/maps/hyundai/neev/usa/260507 "$HMGAPI"/maps/hyundai/neev23/usa/260903; do
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
    has "${m#$HMGAPI/maps/}: every point the mod uses is mapped" "all " "$r"
done

echo "--- plan on NEEV23 260507:"; sed 's/^/    /' "$T/plan-NEEV23.USA.0000.V015.012.260507.txt"

# the recipe: parses, and no region overlaps the Wireless mod's recipes
cat > "$T/R.java" <<'J'
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
javac -cp "$CP" -d "$CP" "$T/R.java"
set +e; r=$(java -cp "$CP" R "$MOD/recipes/ev-sensor-48ee793a.recipe" "$HMGAPI"/mods/wireless-carplay-aa/recipes/aa-tbt-*.recipe 2>&1); rc=$?; set -e
has "recipe parses (4 verify, 3 patch)" "4 verify, 3 patch" "$r"
has "recipe clear of the Wireless mod's" "clear of aa-tbt-48ee793a" "$r"
[ $rc -eq 0 ] || { echo "FAIL  recipe check: $r"; fail=1; }
exit $fail
