# egmp-aa-ev-feat (HMG-API)

Real EV routing in Google Maps on Android Auto for E-GMP cars with Gen5W head units (Hyundai Ioniq 5 and Ioniq 6, Kia EV6): battery % on arrival, charging stops, realistic charge times, and automatic battery preconditioning on the way to a fast charger.

This fork runs it as a mod for [HMG-API](https://github.com/VysseCodes/HMG-API), the successor of the archived HMG Leafia Plus: nothing on the head unit is changed, and firmware support comes from HMG-API's per-car hook maps. The original NEMESIS + Leafia package is still here ([below](#legacy-nemesis--leafia-package)).

> [!WARNING]
> **The HMG-API mod hasn't run on a car yet** (checked off the car only). It changes how the car's Android Auto app behaves while it runs and can ask the car to condition the battery; you use it at your own risk. Not affiliated with Hyundai, Kia or Google.

## Requirements

- An EV HMG-API has a hook map for, any region and software. Made for E-GMP: Hyundai Ioniq 5 (NEEV, NEEV23, NEEV23SE), Kia EV6 (CVEV) and Hyundai Ioniq 6 (CEEV). It also installs on Hyundai's and Kia's other EVs (Kona Electric `OSEV`, Niro EV `DE20EV` `DE22EV` `DEEVPBV` `SG2EV` `SG225EV`, Soul EV `SK3EV` `SK3EV21` `SK3EV22`, Ioniq Electric `AEPEEV`), which **start from E-GMP values and need time to learn the car** (see [Other EVs](#limits)). Hybrids, plug-in hybrids and gas cars aren't offered it. Which builds HMG-API maps: its supported cars list; another build: HMG-API's "Map this car from its files", then **Install anyway…**
- [HMG-API](https://github.com/VysseCodes/HMG-API) 1.0.0 or newer, installed through [NEMESIS](https://github.com/xwtk/hmg_nemesis)
- Preconditioning needs Maps' route data, which an Android Auto library patch hands over. Two builds, one or the other (each refuses the other):
  - **`egmp-aa-ev`**: with HMG-API's **Wireless CarPlay + Android Auto** mod (2.1.0 or newer; HMG-API won't load this build without it), whose library patch gives the route data.
  - **`egmp-aa-ev-standalone`**: without the Wireless mod: wired Android Auto, or the car's own wireless Android Auto. It carries the Wireless mod's route data patch itself ([credits](#credits)), on the library builds that patch exists for: US 260507 (Ioniq 5, EV6, Ioniq 6), the Canadian Ioniq 5's 260206, and the Ioniq 6's (US 260903, Canada). On another build everything but preconditioning runs. It stays off while the Wireless mod is on (it would take that mod's patch): use `egmp-aa-ev` then.
- The energy model needs an Android Auto library build the mod can patch: the two it ships a patch for (`48ee793a`: Ioniq 5 and EV6 US 260507, EV6 Canada 260206; `dbfe7bd2`: Ioniq 6 US 260903 and Canada 260206), or, with an HMG-API newer than 1.1.0-rc.2, any build whose code at the three patch places is the same, which HMG-API finds on the unit (see [Limits](#limits)). Elsewhere preconditioning, live battery data and the EV logs still run

## Features

- **EV routing:** the head unit sends Maps a vehicle energy model with the battery's live energy, so Maps plans stops and shows arrival %.
- **Real capacity:** usable capacity is learned from the BMS, not assumed from the new-car 77.4 kWh.
- **Maps' battery % matches the dash:** the head unit also tells Maps how the car's battery % relates to the battery's energy (Android Auto sensor 26). On E-GMP the energy runs out at about 2 % on the dash, so without it the percentages Maps shows (now, on arrival, at charging stops) read up to 2 points low near empty. US 260507 library build (Ioniq 5 and EV6) for now.
- **Cold weather:** live outside temperature raises heating and rolling losses in the model.
- **Charge times:** calibrated on real DC sessions and the car's own estimate, including the slow last 10–20 %.
- **Only on an EV it fits:** before anything goes to Maps, the mod checks the car reports a battery state of charge and a pack of 50–90 kWh (its nominal size, or the size its energy and charge imply). Otherwise the energy model and live battery data stay off, and HMG-API's log says why, so a car the model doesn't fit (an EV9's 99.8 kWh pack, a car without a traction battery) never gets a wrong range.
- **EV connectors:** Maps is told the car is electric with CCS1 and J1772, for charger filtering.
- **Live battery data for phone apps:** the battery %, the range the cluster shows and the odometer go to the phone as Android Auto's fuel and odometer data, so apps that read the car's data through Android Auto (A Better Route Planner, for one) show the live battery level. No library patch needed, so it works on any Android Auto library build.
- **Preconditioning:** when the next stop is a charger over 50 kW within an hour, the car is asked to warm the battery, through HMG-API's reviewed `conditioning` command. The minimum power, the start window, the arrival distance and the starts per route are settings. Off with the mod's setting or the car's own *battery conditioning* setting. Both builds; the standalone one where it has the route data patch for the car's library build.
- **Learns your car:** steady highway drives (10 km at 60 km/h or more) teach the model this car's real road load, between 0.7× and 1.3× the shipped one. DC fast-charging stops teach it how fast the car really charges in each 10 % SOC band, so Maps' charge times follow the car's own stops. A stop counts only at a charger Maps was guiding to, where the car charged clearly below the charger's power, so a slow post never makes the car look slow. Both apply from the next Android Auto session. Needs EV logs on; turning the setting off forgets it.
- **EV logs:** driving and charging, whenever the head unit is on; copied to any USB drive with an `egmp-aa-ev-logs` folder; share them through an [EV log issue](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api/issues/new?template=log-submission.yml).
- **Settings** in the HMG-API app: energy model, battery % curve, preconditioning (and its thresholds: chargers faster than 50 kW, start within 60 min, arrived within 200 m, 3 starts per route), live battery data, learning this car's highway consumption, EV logs. All on by default; the thresholds apply at once, the rest when Android Auto restarts.

## Install

1. **Remove the NEMESIS versions first:** in NEMESIS Manager (press NAV three times), uninstall `aa.ev.model` and HMG Leafia Plus, then reboot. They replace the Android Auto files HMG-API expects stock, and two energy models would go to Maps.
2. Install [HMG-API](https://github.com/Open-HMG/HMG-API/releases), and for the full build its Wireless CarPlay + Android Auto mod.
3. **Let hooks run:** the EV6 US 260507 and Ioniq 6 US 260903 maps are verified on a car; the others still need the on-car check. In the HMG-API app's **Settings**, tap **Check the map on this car** (hooks run once it passes and the head unit restarts), or turn on **Try unchecked maps** (at the next restart; the boot guard turns hooks off if the unit fails to start three times). No adb needed. Ioniq 6 support requires an HMG-API build that includes the CEEV US 260903 map.
4. Download `egmp-aa-ev-<version>.zip` (or `egmp-aa-ev-standalone-<version>.zip`, without the Wireless mod) from [Releases](../../releases), put it in a folder `HMGAPI/updates/` on a USB stick, plug it in, and tap **Check USB updates** in the HMG-API app.
   **Or through NEMESIS:** unzip `egmp-aa-ev-<version>-nemesis.zip` onto an empty USB stick and plug it in, then confirm in NEMESIS. It installs as NEMESIS package `hmgapi.egmp-aa-ev` (needs HMG-API 1.0 or newer and its Wireless CarPlay + Android Auto mod), or `hmgapi.egmp-aa-ev-standalone`, and HMG-API's engine does the install, so it's the same mod either way. NEMESIS Manager can then update or uninstall it.
5. (Full build) The first time it preconditions, HMG-API asks on screen to allow `conditioning` for this mod: allow it.

Updates install the same way. To remove it: HMG-API app → the mod → remove. Details, hook points and logs to check: [hmgapi-mod/README.md](hmgapi-mod/README.md).

### Limits

- **Energy model library builds:** fixed patches for `libautoreceiver_infobank.so` md5 `48ee793a…` (Ioniq 5 and EV6 US 260507, and the EV6 Canada 260206) and `dbfe7bd2…` (Ioniq 6 US 260903 and Canada 260206). The Canadian EV6 and Ioniq 6 builds were identified by [Weab-chan's Canada test fork](https://github.com/Weab-chan/egmp-aa-ev-hmg-api/tree/ioniq-6), whose owner reported the Ioniq 6 working with this patch. For every other build there's one relocatable patch ([`recipes/ev-sensor.sites`](hmgapi-mod/recipes/ev-sensor.sites), HMG-API's `native_sites`): HMG-API after 1.1.0-rc.2 looks for the three places it changes by what the code there looks like (call and branch offsets may differ) and writes nothing unless each is found exactly once. It was made from the US 260507 build and checked there; whether another build's code matches is only known on that car: the mod's log says `ev-sensor-sites … in place`, or why not. It accepts sensor types up to 23, so the battery % curve (26) stays on the fixed-patch builds. On a build it doesn't fit, or an older HMG-API, the model stays off; fuel type, connectors and live battery data still reach Maps, and preconditioning and the logs run. A build can still get a fixed recipe ([`tools/mkrecipe.py`](hmgapi-mod/tools/mkrecipe.py)).
- **Other EVs (not E-GMP):** the Kona Electric, Niro EV, Soul EV and Ioniq Electric get the Ioniq 5's model to start from. The mod's EV check still turns it off on a pack outside 50–90 kWh (the Ioniq Electric's, the 39 kWh Kona's). Then it **needs time to learn the car**: its highway consumption from steady drives (10 km at 60 km/h or more; on these cars the shipped values count for less, so it's most of the way after about 200 km) and its usable capacity from a charge above 60 %. Until then Maps' arrival % can be well off. Charge times start from E-GMP's 800 V curve, much faster than a 400 V car charges, so Maps plans stops too short until the car has made a DC stop or two at a charger Maps guided it to; on these cars one stop moves most of the way. AC charge times aren't learned. The EV connectors sent are CCS1 and J1772. Use **`egmp-aa-ev-standalone`** on these cars: the Wireless CarPlay + Android Auto mod (2.2.0-rc.5) supports only E-GMP cars (and a few others that aren't EVs), so the full build, which requires it, doesn't load, and preconditioning isn't available: the standalone build's route data patch exists only for the E-GMP library builds. Logs from these cars (see the log issue) would let them get their own values.
- **Preconditioning and HMG-API 1.0's checks:** HMG-API refuses a start when the car isn't READY, Battery Conditioning is off, or the pack is already warm (21 °C / 70 °F on an Ioniq 5), and stops a request if the car's indicator doesn't come on within 2 minutes. After such a timeout, automatic starts wait for a manual one. egmp then cancels for that charging stop, and preconditions again after you start conditioning by hand once.
- **EV6 calibration:** the EV6 has its own road load, fitted on a 2022 EV6's logs (0.8.0), with the Ioniq 5's 77.4-kWh pack and 800 V charging model and its usable capacity learned from the BMS.
- **Ioniq 5 calibration:** the Ioniq 5's road load is fitted on a 2023 Ioniq 5's interstate logs (issue #22): 16 % less energy at highway speed than the rolldown values it used before. Those logs were from a single-motor car above 15 °C; AWD and winter logs would refine it.
- **Ioniq 6 calibration:** the Ioniq 6 initially uses the same 77.4-kWh E-GMP battery and charging model as the Ioniq 5. Live BMS energy immediately teaches it the car's usable capacity. Its road load is the Ioniq 5 rolldown's, which is conservative for the more aerodynamic Ioniq 6; Ioniq 6 EV logs are needed to tune arrival-energy predictions precisely.

What HMG-API offers and lacks for EV routing: [docs/HMG-API-COMPARISON.md](docs/HMG-API-COMPARISON.md).

## EV logs

The head unit records a small log whenever the car is on, driving or charging: battery energy and power, speed, temperatures, climate, charging and Maps' route plan. No location, no full VIN, no times of day. It keeps up to 100 MB, oldest first out.

To collect and share them:

1. Make a folder named `egmp-aa-ev-logs` at the top level of any FAT32/exFAT USB drive.
2. With the car on, plug the drive into the USB data port. Within about 20 seconds a pop-up says how many logs were copied ("… Safe to remove"). Turn logging off with the mod's *EV logs* setting.
3. Open an [EV log issue](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api/issues/new?template=log-submission.yml) and drag the `egmp-*.csv` files from that folder into it. Issues are public.

Logs from different cars, climates and battery ages are what improve the energy model most. Format details: [LOGGING](docs/LOGGING.md).

## Docs

| | |
|---|---|
| [ENERGY-MODEL](docs/ENERGY-MODEL.md) | the energy model and its calibration |
| [PRECONDITIONING](docs/PRECONDITIONING.md) | how preconditioning works |
| [LOGGING](docs/LOGGING.md) | EV log format and `tools/evlog.py` |
| [DATA-SOURCES](docs/DATA-SOURCES.md) | vehicle data the head unit exposes |
| [RESEARCH](docs/RESEARCH.md) | head unit internals and the patch design |
| [HANDOFF](docs/HANDOFF.md) | developer guide for the NEMESIS package: build, install, troubleshooting |
| [ROADMAP](docs/ROADMAP.md) | what's next |
| [HMG-API-COMPARISON](docs/HMG-API-COMPARISON.md) | the HMG-API mod against HMG-API's built-ins: data, features, gaps |

## Building and releasing

The HMG-API mod builds from this repo with the [HMG-API mod SDK](https://github.com/Open-HMG/HMG-API/blob/main/tools/sdk/README.txt) that [`hmgapi-sdk.json`](hmgapi-sdk.json) pins (a release and its SHA-256, the pin HMG-API's reusable mod workflow uses). [`hmgapi-mod/sdk.sh`](hmgapi-mod/sdk.sh) fetches and checks it, with HMG-API's hook maps for the tests, into `.hmgapi/`. The tests also want the [Wireless CarPlay + Android Auto](https://github.com/Open-HMG/hmgapi-wireless-cp-aa) mod's repo next to this one. No car files:

```sh
sh hmgapi-mod/build.sh                                         # build/mod/egmp-aa-ev{,-standalone}/
WIRELESS=../hmgapi-wireless-cp-aa/wireless-carplay-aa \
  sh hmgapi-mod/test/run.sh                                     # every off-car test, both builds
sh hmgapi-mod/build.sh ~/hmg-keys/<key>                        # + dist/<id>-<version>.zip, signed
```

Both builds come from the same sources: [`hmgapi-mod/variants.py`](hmgapi-mod/variants.py) makes the standalone one's `mod.json` and `settings.json` (no `requires`, conditioning command, relay service or preconditioning settings). To move to a newer HMG-API, change `hmgapi-sdk.json` (and `WIRELESS_REF` in both workflows); CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) builds and tests every push and pull request.

**Releases** are built by CI ([`.github/workflows/release.yml`](.github/workflows/release.yml)): set `"version"` in `hmgapi-mod/mod.json`, merge, then create a release in GitHub's UI with the tag `v<version>`. CI checks the tag matches, runs the tests, builds both mods signed with the `HMGAPI_AUTHOR_KEY` secret (your HMG-API author key file's contents) and attaches each one's zip and NEMESIS packages to the release. More: [hmgapi-mod/README.md](hmgapi-mod/README.md#releasing).

## Legacy: NEMESIS + Leafia package

The original package (`gpm-ev/`, `packages/`, `release/`), for a head unit with NEMESIS and **HMG Leafia Plus v1.0.5** on US 260507 (`NEEV.USA.0000.V015.012.260507`). It modifies the Android Auto app's files: the installer checks every file first and keeps a byte-for-byte backup.

1. Download the `-usb.zip` from [Releases](../../releases) and copy its **contents** to an empty FAT32/exFAT drive.
2. Car on, plug the drive into a USB data port, tap **Yes** when NEMESIS asks. The head unit restarts when it's done.

To uninstall: NEMESIS Manager → `aa.ev.model` → uninstall, then reboot; Leafia's files are restored exactly. More in the release's [README.txt](release/README.txt). The release contains no Hyundai or Google files, only small binary deltas that [`lpatch`](third_party/lpatch) applies to your unit's own files.

Building it patches files from your own head unit (not in this repo); see [HANDOFF](docs/HANDOFF.md) for what goes in `private/` and the toolchain.

```sh
PY=/path/to/python sh tools/build_all.sh   # build everything
sh gpm-ev/test/run.sh                      # Java host tests
bash tools/test_package.sh                 # package install/uninstall sandbox
sh tools/test_evlog.sh                     # EV log tools
sh tools/build_release.sh v0.2.0           # USB release zip
```

## Credits

[HMG-API](https://github.com/VysseCodes/HMG-API) (VysseCodes), [NEMESIS](https://github.com/xwtk/hmg_nemesis) (xwtk), [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus) (including `lpatch`, GPL-3.0), [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs), [open-android-auto](https://github.com/mrmees/open-android-auto), [gen5w-preconditioning](https://github.com/paytondevs/gen5w-preconditioning).

The standalone build's route data patch is the [Wireless CarPlay + Android Auto](https://github.com/Open-HMG/hmgapi-wireless-cp-aa) mod's turn-by-turn library recipes, copied unchanged ([hmgapi-mod/route/](hmgapi-mod/route/README.md)): US 260507 and the Canadian Ioniq 5 by **VysseCodes** (from NEMESIS's `projection.tbt` package), the Ioniq 6 by **Weab-chan**. Weab-chan's Canadian Ioniq 6 and EV6 test build also found the Android Auto shutdown crash and the other fixes in [#23](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api/pull/23).

## License

[AGPL-3.0](LICENSE), the same as HMG-API. `third_party/lpatch` stays GPL-3.0 (its own [LICENSE](third_party/lpatch/LICENSE)), which the AGPL allows combining with.
