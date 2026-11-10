# egmp-aa-ev-feat (HMG-API)

Real EV routing in Google Maps on Android Auto for E-GMP cars with Gen5W head units (Hyundai Ioniq 5 and Ioniq 6, Kia EV6): battery % on arrival, charging stops, realistic charge times, and automatic battery preconditioning on the way to a fast charger.

This fork runs it as a mod for [HMG-API](https://github.com/VysseCodes/HMG-API), the successor of the archived HMG Leafia Plus: nothing on the head unit is changed, and firmware support comes from HMG-API's per-car hook maps. The original NEMESIS + Leafia package is still here ([below](#legacy-nemesis--leafia-package)).

> [!WARNING]
> **The HMG-API mod hasn't run on a car yet** (checked off the car only). It changes how the car's Android Auto app behaves while it runs and can ask the car to condition the battery; you use it at your own risk. Not affiliated with Hyundai, Kia or Google.

## Requirements

- An E-GMP car HMG-API has a hook map for: Hyundai Ioniq 5 (NEEV, NEEV23, NEEV23SE), Kia EV6 (CVEV) or Hyundai Ioniq 6 (CEEV), any region and software. Today HMG-API maps the Ioniq 5 and EV6 in the US (260507) and Canada (260206) and the Ioniq 5 US 260903; the Ioniq 6 US 260903 runs with its own map. Another car: HMG-API's "Map this car from its files", then **Install anyway…**
- [HMG-API](https://github.com/VysseCodes/HMG-API) 1.0.0 or newer, installed through [NEMESIS](https://github.com/xwtk/hmg_nemesis)
- HMG-API's **Wireless CarPlay + Android Auto** mod, 2.1.0 or newer. HMG-API won't load this mod without it: its turn-by-turn adds Maps' route data, which preconditioning needs
- The energy model needs a supported Android Auto library build: US 260507 (Ioniq 5 and EV6) or Ioniq 6 US 260903 (see [Limits](#limits)). Elsewhere preconditioning and the EV logs still run

## Features

- **EV routing:** the head unit sends Maps a vehicle energy model with the battery's live energy, so Maps plans stops and shows arrival %.
- **Real capacity:** usable capacity is learned from the BMS, not assumed from the new-car 77.4 kWh.
- **Maps' battery % matches the dash:** the head unit also tells Maps how the car's battery % relates to the battery's energy (Android Auto sensor 26). On E-GMP the energy runs out at about 2 % on the dash, so without it the percentages Maps shows (now, on arrival, at charging stops) read up to 2 points low near empty. US 260507 library build (Ioniq 5 and EV6) for now.
- **Cold weather:** live outside temperature raises heating and rolling losses in the model.
- **Charge times:** calibrated on real DC sessions and the car's own estimate, including the slow last 10–20 %.
- **Only on an EV it fits:** before anything goes to Maps, the mod checks the car reports a battery state of charge and a pack of 50–90 kWh (its nominal size, or the size its energy and charge imply). Otherwise the energy model and live battery data stay off, and HMG-API's log says why, so a car the model doesn't fit (an EV9's 99.8 kWh pack, a car without a traction battery) never gets a wrong range.
- **EV connectors:** Maps is told the car is electric with CCS1 and J1772, for charger filtering.
- **Live battery data for phone apps:** the battery %, the range the cluster shows and the odometer go to the phone as Android Auto's fuel and odometer data, so apps that read the car's data through Android Auto (A Better Route Planner, for one) show the live battery level. No library patch needed, so it works on any Android Auto library build.
- **Preconditioning:** when the next stop is a charger over 50 kW within an hour, the car is asked to warm the battery, through HMG-API's reviewed `conditioning` command. The minimum power, the start window, the arrival distance and the starts per route are settings. Off with the mod's setting or the car's own *battery conditioning* setting.
- **EV logs:** driving and charging, whenever the head unit is on; copied to any USB drive with an `egmp-aa-ev-logs` folder; share them through an [EV log issue](https://github.com/willemcvu/egmp-aa-ev-feat/issues/new?template=log-submission.yml).
- **Settings** in the HMG-API app: energy model, battery % curve, preconditioning (and its thresholds: chargers faster than 50 kW, start within 60 min, arrived within 200 m, 3 starts per route), live battery data, EV logs. All on by default; the thresholds apply at once, the rest when Android Auto restarts.

## Install

1. **Remove the NEMESIS versions first:** in NEMESIS Manager (press NAV three times), uninstall `aa.ev.model` and HMG Leafia Plus, then reboot. They replace the Android Auto files HMG-API expects stock, and two energy models would go to Maps.
2. Install [HMG-API](https://github.com/VysseCodes/HMG-API/releases) and its Wireless CarPlay + Android Auto mod.
3. **Let hooks run:** the EV6 US 260507 and Ioniq 6 US 260903 maps are verified on a car; the others still need the on-car check. In the HMG-API app's **Settings**, tap **Check the map on this car** (hooks run once it passes and the head unit restarts), or turn on **Try unchecked maps** (at the next restart; the boot guard turns hooks off if the unit fails to start three times). No adb needed. Ioniq 6 support requires an HMG-API build that includes the CEEV US 260903 map.
4. Download `egmp-aa-ev-<version>.zip` from [Releases](../../releases), put it in a folder `HMGAPI/updates/` on a USB stick, plug it in, and tap **Check USB updates** in the HMG-API app.
   **Or through NEMESIS:** unzip `egmp-aa-ev-<version>-nemesis.zip` onto an empty USB stick and plug it in, then confirm in NEMESIS. It installs as NEMESIS package `hmgapi.egmp-aa-ev` (needs HMG-API 1.0 or newer and its Wireless CarPlay + Android Auto mod), and HMG-API's engine does the install, so it's the same mod either way. NEMESIS Manager can then update or uninstall it.
5. The first time it preconditions, HMG-API asks on screen to allow `conditioning` for this mod: allow it.

Updates install the same way. To remove it: HMG-API app → the mod → remove. Details, hook points and logs to check: [hmgapi-mod/README.md](hmgapi-mod/README.md).

### Limits

- **Energy model library builds:** US 260507, Ioniq 5 and EV6 (`libautoreceiver_infobank.so`, md5 `48ee793a…`), and Ioniq 6 US 260903 (md5 `dbfe7bd2…`). On another build (the Canadian cars' `a0bea1c4…`, and possibly the Ioniq 5 US 260903) nothing is patched and the model stays off; fuel type, connectors and live battery data still reach Maps, and preconditioning and the logs run. Another build needs a recipe ([`tools/mkrecipe.py`](hmgapi-mod/tools/mkrecipe.py)).
- **Preconditioning and HMG-API 1.0's checks:** HMG-API refuses a start when the car isn't READY, Battery Conditioning is off, or the pack is already warm (21 °C / 70 °F on an Ioniq 5), and stops a request if the car's indicator doesn't come on within 2 minutes. After such a timeout, automatic starts wait for a manual one. egmp then cancels for that charging stop, and preconditions again after you start conditioning by hand once.
- **EV6 calibration:** the EV6 uses the Ioniq 5 model: the same 77.4-kWh E-GMP pack and 800 V charging, with its usable capacity learned from the BMS. Its road load is close to the Ioniq 5's (a little more efficient), so arrival % should be slightly conservative. EV6 logs would let us tune it.
- **Ioniq 6 calibration:** the Ioniq 6 initially uses the same 77.4-kWh E-GMP battery and charging model as the Ioniq 5. Live BMS energy immediately teaches it the car's usable capacity. Its Ioniq 5 road-load calibration is conservative for the more aerodynamic Ioniq 6; Ioniq 6 EV logs are needed to tune arrival-energy predictions precisely.

What HMG-API offers and lacks for EV routing: [docs/HMG-API-COMPARISON.md](docs/HMG-API-COMPARISON.md).

## EV logs

The head unit records a small log whenever the car is on, driving or charging: battery energy and power, speed, temperatures, climate, charging and Maps' route plan. No location, no full VIN, no times of day. It keeps up to 100 MB, oldest first out.

To collect and share them:

1. Make a folder named `egmp-aa-ev-logs` at the top level of any FAT32/exFAT USB drive.
2. With the car on, plug the drive into the USB data port. Within about 20 seconds a pop-up says how many logs were copied ("… Safe to remove"). Turn logging off with the mod's *EV logs* setting.
3. Open an [EV log issue](https://github.com/willemcvu/egmp-aa-ev-feat/issues/new?template=log-submission.yml) and drag the `egmp-*.csv` files from that folder into it. Issues are public.

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

The HMG-API mod builds from this repo alone, with an [HMG-API](https://github.com/VysseCodes/HMG-API) checkout next to it (no car files):

```sh
HMGAPI=../HMG-API sh hmgapi-mod/build.sh                       # build/mod/egmp-aa-ev/
HMGAPI=../HMG-API sh hmgapi-mod/test/run.sh                    # every off-car test
HMGAPI=../HMG-API sh hmgapi-mod/build.sh ~/hmg-keys/<key>      # + dist/egmp-aa-ev-<version>.zip, signed
```

**Releases** are built by CI ([`.github/workflows/release.yml`](.github/workflows/release.yml)): set `"version"` in `hmgapi-mod/mod.json`, merge, then create a release in GitHub's UI with the tag `v<version>`. CI checks the tag matches, runs the tests, builds the mod signed with the `HMGAPI_AUTHOR_KEY` secret (your HMG-API author key file's contents) and attaches `egmp-aa-ev-<version>.zip` to the release. More: [hmgapi-mod/README.md](hmgapi-mod/README.md#releasing).

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

## License

[AGPL-3.0](LICENSE), the same as HMG-API. `third_party/lpatch` stays GPL-3.0 (its own [LICENSE](third_party/lpatch/LICENSE)), which the AGPL allows combining with.
