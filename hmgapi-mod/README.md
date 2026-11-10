# egmp-aa-ev as an HMG-API mod

The same EV routing as the NEMESIS package (energy model for Google Maps, automatic
battery preconditioning on the way to a DC charger, EV logs), as a mod for
[HMG-API](https://github.com/VysseCodes/HMG-API), the replacement for HMG Leafia Plus.
Nothing in the head unit's files is changed: HMG-API hooks the Android Auto app (GPM)
while it runs and patches its library in memory.

> [!WARNING]
> **Not tested on a car yet.** Everything below is checked off the car only
> (`test/run.sh`). Read [docs/HMG-API-COMPARISON.md](../docs/HMG-API-COMPARISON.md)
> for what's known and what isn't.

## How it maps onto HMG-API

| Part | What it is |
|---|---|
| `mod.json` | id `egmp-aa-ev`; every E-GMP model HMG-API can map, any region and firmware: Ioniq 5 (NEEV, NEEV23, NEEV23SE), EV6 (CVEV), Ioniq 6 (CEEV). Hooks run where HMG-API has a map, the energy model where a recipe matches the library and the EV check passes; hook points, library recipes, Android Auto fuel/connector types, the `conditioning` command, the USB folder, two services |
| `hooks.jar` (`src/`) | in GPM: `HmgApiEntry` (the hooks), `EvModelSender`, `Precond`, `EvCar` over `HmgCar` (the car's services by name through HMG-API's hook map), `NativeCheck`, `Relay`; plus NavProto, PrecondCore, EvRowAgg, EvLogger, EvModelTemplate and EvDriveLog shared with the NEMESIS build (`gpm-ev/src`) |
| `recipes/*.recipe` | egmp's three Android Auto library patches as HMG-API memory recipes, one per library build (`tools/mkrecipe.py`; US 260507, Ioniq 5 and EV6, and Ioniq 6 US 260903), each clear of the matching Wireless mod turn-by-turn recipe. `NativeCheck` reads them itself, so another build is one more recipe here and in `mod.json` |
| `relay.jar` (`service/`) | service `relay` (user `system`): sends Precond's requests as HMG-API's `conditioning` command, which HMG-API answers only for a mod's own service |
| `scripts/offload.sh` | service `offload` (root): copies EV logs to a USB drive with an `egmp-aa-ev-logs` folder |
| `settings.json` | `energy_model`, `preconditioning`, `live_data`, `ev_log` (all on), and the preconditioning thresholds `precond_min_kw` (50), `precond_window_min` (60), `precond_arrival_m` (200), `precond_max_starts` (3): `PrecondCore.Config`, applied at once |

Hook points used (all in HMG-API 1.0.0, mapped on every HMG-API map: Ioniq 5 and EV6 in the US and Canada, Ioniq 5 US 260903, and the Ioniq 6 US 260903 map):
`gpm.sensor_source.init` (change: sensor 23 when the library patch is in place, and
the fuel (6) and odometer (5) sensors for the live battery data, both only when the EV
check passes: a battery state of charge, and a 50-90 kWh pack by its nominal size or
energy / SOC (`EvCar.notAnEvWeFit`); start the senders and
preconditioning), `gpm.receiver.destroy` and
`gpm.receiver_service.disconnected` (stop them), `gpm.navigation_status.next_turn`
(Maps' route messages, event 33), `gpm.navigation_status.status` (guidance state).

## Build

Needs a JDK, the Android SDK (build-tools and a platform `android.jar`; `ANDROID_HOME`)
and an HMG-API checkout (`HMGAPI`, default `../HMG-API`). No car files.

```bash
HMGAPI=../HMG-API sh hmgapi-mod/build.sh
```

```bash
HMGAPI=../HMG-API sh hmgapi-mod/test/run.sh
```

The first gives `build/mod/egmp-aa-ev/`. With your HMG-API author key it also signs the
package, `dist/egmp-aa-ev-<version>.zip`. Make a key once, keep it private and backed up
(a lost key means owners must remove the mod before installing a new one):

```bash
java -cp ../HMG-API/out/engine-host hmgapi.engine.Signing keygen ~/hmg-keys/egmp.key
```

```bash
HMGAPI=../HMG-API sh hmgapi-mod/build.sh ~/hmg-keys/egmp.key
```

## Install (on a car with HMG-API)

1. **Remove the NEMESIS build first** (NEMESIS Manager → `aa.ev.model` → uninstall,
   reboot), and HMG Leafia Plus: both replace the files HMG-API hooks, and two energy
   models would go to Maps.
2. HMG-API 1.0.0 or newer, with its **Wireless CarPlay + Android Auto** mod
   (its turn-by-turn adds Maps' route data, which preconditioning needs).
3. Hook map: the EV6 US 260507 and Ioniq 6 US 260903 maps are verified; the others
   still need the on-car check. In the HMG-API app's **Settings**, tap **Check the map on this car**
   (hooks run once it passes and the head unit restarts), or turn on **Try unchecked
   maps** (at the next restart; the boot guard turns hooks off if the unit fails to
   start three times). Making a Car Report runs the same check. Ioniq 6 support needs
   an HMG-API build containing the CEEV US 260903 map. No adb needed.
4. Download `egmp-aa-ev-<version>.zip` from the repo's Releases (or build it, above), put
   it in `HMGAPI/updates/` on a USB stick, then **Check USB updates** in the HMG-API app
   (or install it over adb). Until the author key is on HMG-API's trusted list
   (`maps/authors.json`), HMG-API installs it with a warning.
5. The first automatic preconditioning asks you on screen to allow `conditioning` for
   this mod.

What to look at on the car: HMG-API's log for `[egmp-aa-ev]` lines (the native patch
"applied", "EV routing hooks in ..."), logcat tags `EvModelSender` ("energy model sent")
and `EvPrecond`, and `hmgapid service-log relay`. "energy model off: the Android Auto
library patch isn't in place" means this car's library isn't one of the supported
builds (US 260507 for the Ioniq 5 and EV6, Ioniq 6 US 260903). "energy model and live
battery data off on this car: ..." is the EV check, with the reason.

## Releasing

Releases are built by CI, from a release created in GitHub's UI
(`.github/workflows/release.yml`):

1. Set `"version"` in `hmgapi-mod/mod.json` (e.g. `0.3.1`) and merge it to `main`.
2. In GitHub: **Releases → Draft a new release**, tag `v0.3.1` (the same version), publish.
3. CI checks the tag matches `mod.json`, checks out HMG-API (`HMGAPI_REF` in the
   workflow, a release tag), runs `test/run.sh`, builds the mod signed with your author
   key and attaches `egmp-aa-ev-0.3.1.zip` to the release, with its NEMESIS packages
   (HMG-API's `tools/mknemesismod.sh`): `egmp-aa-ev-0.3.1-nemesis.zip` for a USB stick and
   `egmp-aa-ev-0.3.1-nemesis-package.zip` for the NEMESIS Package Store. A failed check
   leaves the release without them; fix it and re-run the workflow.

Repository secret it needs (Settings → Secrets and variables → Actions):

| Secret | What |
|---|---|
| `HMGAPI_AUTHOR_KEY` | the contents of your HMG-API author key file (`Signing keygen`, one line; CI derives the `.pub` from it). Every version must be signed with the same key: HMG-API only updates a mod from the key that installed it. Keep the file backed up outside GitHub too. |

The mod's id comes with a UUID made from that key (`java -cp ../HMG-API/out/engine-host
hmgapi.engine.Signing uuid <key file>`); to have HMG-API list it as a trusted author,
open an issue on HMG-API with the UUID and where the mod is published.
