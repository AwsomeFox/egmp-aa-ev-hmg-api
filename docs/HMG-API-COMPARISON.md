# egmp-aa-ev and HMG-API: features and data for EV routing

Date: 2026-11-03. Compares the NEMESIS build of egmp-aa-ev (v0.2.1, `gpm-ev/`,
`packages/aa-ev-model/`), HMG-API 1.0.0 (VysseCodes/HMG-API `e999d82`) and
the HMG-API mod in `hmgapi-mod/` (0.3.0). Everything here is off-car: nothing in
the mod has run on a head unit yet.

## 1. What Google Maps gets, and from where

| Routing data / feature | NEMESIS build | HMG-API built in | HMG-API mod (this port) |
|---|---|---|---|
| Fuel type EV (`FUEL_TYPE_ELECTRIC` 10) | smali edit in `SensorSource.init` | `projection.aa_fuel_types` | uses HMG-API's (`mod.json`) |
| EV connectors (CCS1 4, J1772 1) | smali edit | `projection.aa_ev_connectors` | uses HMG-API's |
| Sensor 23 offered (vehicle energy model) | smali edit (`nativeRegisterSensor(23)`) | `projection.aa_sensors.add` | own `change` hook on `gpm.sensor_source.init`, **only once the library patch is confirmed in memory** (see 3.6) |
| Library accepts/answers sensor 23, raw send path | 3-site file delta of the Leafia library | none (the mod's own `hooks.native`) | same 3 sites as per-build in-memory recipes (`ev-sensor-48ee793a` for Ioniq 5 US 260507, `ev-sensor-dbfe7bd2` for Ioniq 6 US 260903), each checked clear of the matching Wireless mod recipe |
| Energy model (capacity, energy, charge curves, aux/HVAC, cold road load) every 5 s | `EvModelSender` | none ("its energy-model sender" stays the mod's, api-plan) | `EvModelSender`, same model and template |
| Usable capacity learned from the BMS (energy / SOC) | yes | none | yes (same file in GPM's files dir, so it carries over from the NEMESIS build) |
| Outside temperature for the aux table and cold road load | nav battery packet | reading `outside_temp` (climate control's) | nav battery packet, as before (same decode, verified against the dash) |
| Automatic preconditioning to a DC charger > 50 kW within 1 h | `Precond` + raw HEV writes 62/65/66 on a sha256-verified framework | command `conditioning` (route values since 1.0.0) | `Precond` deciding, HMG-API's command sending (through the relay service), **real distance and minutes** with the HMG-API patch in `hmgapi-mod/hmg-api-patches/` |
| Maps' route plan (destinations, charging stops, remaining distance/time: 0x8006/0x8007) | Leafia's `NAVDUMP` in `GpmNaviTBTManager` | events `aa_navigation_message`, to a mod's **app** only, from the Wireless mod's library patch | the same messages (event 33) read on `gpm.navigation_status.next_turn`; needs the Wireless CarPlay + Android Auto mod's turn-by-turn |
| EV logs (1 s energy/speed, 5 s rows, charging, climate, drive mode, altitude, Maps plan) | `EvDriveLog` + root offload script | none | same logger in GPM; offload as a mod service with HMG-API's USB folder (`"usb": ["egmp-aa-ev-logs"]`) |
| Off switch, settings | `persist.gpm.ev.off=1` | per-mod switch and settings page | HMG-API's switch, plus settings `energy_model`, `preconditioning`, `ev_log` and the preconditioning thresholds (minimum charger power, start window, arrival distance, starts per route), applied at once |
| Firmware support | Leafia v1.0.5 on US 260507 only (md5-locked) | per-car hook maps | US Ioniq 5 on 260507 or newer and US Ioniq 6 on 260903 (`mod.json`), hooks wherever HMG-API has a map; the energy model on the Ioniq 5 US 260507 and Ioniq 6 US 260903 library builds |

## 2. Car data: what egmp reads and what HMG-API offers as readings

The port reads everything egmp read, but through HMG-API's hook map by name (`HmgCar`)
instead of binder transaction numbers. HMG-API's own readings (for a mod's app or
service, via `hmgapid vehicle`) cover only some of it:

| Signal (egmp source) | Used for | HMG-API reading | Port reads it through |
|---|---|---|---|
| Display SOC (HEV `DISPLAY_SOC`) | model energy, capacity learning, log | `hv.soc` | `framework.hev_service.hev_status` + `framework.hev_flag.display_soc` |
| Usable energy (HEV `ENERGYMAP_1000MS` / nav `EV_AVLBL_ENRG`) | **model energy and capacity** | — | `hev_status` + HEVFlag by name; nav packet |
| Pack min temperature (nav packet) | log (charge curve warmth) | — | `NavigationCluster` battery packet |
| Outside temperature | **model aux table, cold road load** | `outside_temp` | nav packet (as before) |
| Range / DTE (nav packet) | log | — ("range" listed as missing in api-plan) | nav packet |
| BMS SOH (hscan) | log | `hv.health` | `framework.hscan_service.hv_health` |
| Speed (hscan) | log, energy rate | `speed` | `framework.hscan_service.car_speed` |
| Odometer (HEV) | log | `odometer` | `hev_status` + `framework.hev_flag.odometer` |
| Battery conditioning setting (HEV 94) | precond on/off | `hv.conditioning` | `framework.hev_flag.battery_conditioning` |
| EV ready (HEV 44) | precond guard | `hv.ready` | `framework.hev_flag.ev_ready` |
| Conditioning indicator (BCM `getBattCondIndSta`) | **precond: Active / cancel confirmed** | — | BCM interface (mapped `as_interface`), method by name |
| Charging state, fast charging, CHG_STS, target SOC AC/DC, quick-charge minutes, charge current, OBC/VCMS power, battery-heater power and trip energy sums (HEV) | log (charge curve research) | `charging` (one raw flag) | `hev_status` + HEVFlag by name (number fallback) |
| Climate set temp, blower, mode, AC, auto, op state, unit (DATC) | log (HVAC model) | — | DATC interface (mapped `as_interface`), `getVRClimateStatus` by name |
| Drive mode (BCM listener) | log | — | `framework.bcm_service.register_listener`, `TRANSACTION_onDriveModeChanged` by name |
| GPS altitude | log (climb energy) | — | Android `LocationManager` (unchanged) |
| VIN model chars | log header | never (HMG-API: no per-car identity) | **dropped**: the header's product id (`NEEV23.USA...`) says the model |

## 3. Gaps, and what would give both the most for routing

### 3.1 Conditioning route values and the stop latch (merged in 1.0.0)

Before 1.0.0, HMG-API's `conditioning` always told the car "9 km / 18 min to a
station", and any `stop` set the manual-stop latch, so after egmp ended its own request
at the charger every later automatic start was refused until a manual start.
[VysseCodes/HMG-API#47](https://github.com/VysseCodes/HMG-API/pull/47) (merged in
1.0.0) added `start|auto-start [<km> <minutes>]` and `route <km> <minutes>` (the
holder only, 0-2046 km / 0-1022 min, km whatever the car's distance unit: checked on a
US Ioniq 5 2026-11-03), and the stop of the mod whose automatic request it is no
longer latches.

1.0.0 also added checks of its own (Launchpad's): a start is refused when the car
isn't READY, Battery Conditioning is off, the car already conditions on its own, or
the pack's minimum temperature is 21 °C or more on an Ioniq 5 (20 °C others; a last
word `warm` sends it anyway); the station event goes once, at the start; and a request
is stopped if the car's indicator isn't on within 2 minutes (EV6 5), after which
**automatic starts wait for a manual one**. egmp sends without `warm` (the BMS won't
heat a warm pack), and a refusal or a timeout cancels for that charging stop
(RelayTest). The timeout fits a request the car acts on at once, not egmp's, which asks
up to an hour before the charger and lets the BMS time the heating: one early request
the BMS holds off on locks automatic preconditioning out until a manual start. An
HMG-API change (a routed automatic request waits for the car while its route values
count down, and its own timeout doesn't latch) is the fix; until then a 20-30 minute
window (`precond_window_min`) makes it less likely.

The rest of this section is in issues
[#44](https://github.com/VysseCodes/HMG-API/issues/44) (core library recipe and raw
sender), [#45](https://github.com/VysseCodes/HMG-API/issues/45) (readings) and
[#46](https://github.com/VysseCodes/HMG-API/issues/46) (hook points).

### 3.2 Readings worth adding to HMG-API

Each is a getter HMG-API could serve from its existing hook points, so any mod (a
dashboard, Launchpad, BatteryWatchdog) gets them, and egmp's reads become one shared
cache instead of two pollers:

| Proposed reading | Source | Why |
|---|---|---|
| `hv.energy` (Wh) | HEV `ENERGYMAP_1000MS` × 2 Wh | the one number EV routing most needs; `hv.soc` alone can't give energy without capacity |
| `hv.capacity_nominal` (Wh) | HEV `BMS_EVENERGYCAPA` × 2 Wh | |
| `hv.pack_temp_min` (°C) | nav battery packet `XEV_MIN_BATT_TEMP` | precondition decisions and charge-speed estimates |
| `range` (km) | nav packet `VCU_DTE_BASE + VCU_DTE_ADD` | listed as missing in HMG-API's api-plan |
| `hv.conditioning_active` (0/1) | BCM `getBattCondIndSta` | whether the car is actually conditioning (the `conditioning` command can't confirm it today) |
| `charge.power_kw`, `charge.target_ac`, `charge.target_dc`, `charge.minutes_left` | hscan `getOBC_AcPwrDis` / HEV `VCMS_PWRDIS`, `TARGET_SOC_SLOW/QUICK`, quick-charge remaining | charging-stop planning |
| `drive_mode` | BCM listener `onDriveModeChanged` (passive, never `getDriveMode`, which pops up) | consumption model |

### 3.3 Hook points worth adding to HMG-API's catalogue

The port works with today's points, but these would make it cleaner and safer (they
must then be mapped on every car, as #31 did for iAP2):

```json
{"id": "gpm.sensor_source.shutdown", "kind": "method", "of": "gpm.sensor_source",
 "find": {"name": "shutdown", "params": [], "ret": "void"},
 "about": "the sensor service ends; before nativeShutdown frees the native object"},
{"id": "gpm.sensor_source.report_diagnostics", "kind": "method", "of": "gpm.sensor_source",
 "find": {"name": "nativeReportDiagnosticsData", "params": ["byte[]"], "ret": "int"},
 "about": "raw send path once the library's reportDiagnosticsData is patched (EV model)"},
{"id": "framework.datc_service.vr_climate_status", "kind": "method", "of": "framework.datc_service",
 "find": {"name": "getVRClimateStatus", "params": ["int"], "ret": "float"}},
{"id": "framework.datc_service.is_unit_celsius", "kind": "method", "of": "framework.datc_service",
 "find": {"name": "isUnitCelsius", "params": ["int"]}},
{"id": "framework.bcm_service.batt_cond_indicator", "kind": "method", "of": "framework.bcm_service",
 "find": {"name": "getBattCondIndSta", "params": [], "ret": "int"}},
{"id": "framework.bcm_listener_stub.drive_mode", "kind": "field", "of": "framework.bcm_listener_stub",
 "find": {"name": "TRANSACTION_onDriveModeChanged", "type": "int", "static": true}},
{"id": "framework.navigation_cluster.request_battery_info", "kind": "method", "of": "framework.navigation_cluster",
 "find": {"name": "requestBatteryInfoReq", "params": [], "ret": "void"}}
```

The port already prefers `gpm.sensor_source.report_diagnostics` when a map has it.
`gpm.sensor_source.shutdown` matters most: today the sender is stopped on
`gpm.receiver.destroy` and `gpm.receiver_service.disconnected`, which run before
GPM tears the connection down, but a teardown path that calls `SensorSource.shutdown`
without either (not seen, not ruled out) would race the 5 s sender.

### 3.4 Projection API gaps

- **Raw sensor-batch sender in core.** api-plan step 4 lists "a raw sensor-batch
  sender (EGMP's energy model)" and "EGMP's three AA library sites as core native
  recipes". Not done in 1.0.0: the mod carries both. In core, any mod could add a
  sensor type safely, and HMG-API could tie `aa_sensors.add` to the recipe being in
  place (today `add` applies even if the library would drop the type).
- **Navigation messages for hook code.** `aa_navigation_message` goes only to a
  mod's app (a broadcast to its package). A mod without an APK (this one) has to
  read event 33 off `next_turn` itself. A `ProjectionHooks` listener for hook code
  would remove that.
- **Native patch status for mods.** `ModContext.nativePatch` doesn't say whether a
  recipe applied. The port reads its three sites back from `/proc/self/mem`
  (`NativeCheck`) before it offers sensor 23 or sends.

### 3.5 Things only egmp has that HMG-API could take in

The energy model itself (template, capacity learning, charge-curve rescaling,
cold-weather road load) and the preconditioning decision (`PrecondCore`: charger
selection > 50 kW within 1 h, ownership journal, READY/setting/indicator guards, cancel
confirmation) are routing logic, not plumbing; they stay in the mod. The log format
(`docs/LOGGING.md`) is the data set the model is tuned from.

### 3.6 Behaviour differences in the port

- **First conditioning command asks the owner** (HMG-API's default "ask first"): the
  first automatic start is refused and a question appears on screen. `PrecondCore`
  treats a failed start as it always did (cancel, then no more tries for that charging
  stop), so after "Allow" preconditioning works from the next charging stop.
- **A stop only ends egmp's own request.** HMG-API lets any mod stop conditioning, and
  `PrecondCore` cancels after a refused start too. The relay service forwards a stop
  only while egmp holds the request (recorded in its data folder; cleared when HMG-API
  names another holder), so it never ends Launchpad's or the owner's.
- **Hooks run only with a verified map**, or with "testing maps" on: the Ioniq 6 US
  260903 map is car-verified; the Ioniq 5 maps are `mapped` (260507) and `probed`
  (260903). In the HMG-API app: Settings → **Check the map on this car** (or a Car
  Report, which runs the same check), or **Try unchecked maps**. The HMG-API build
  must contain the CEEV map for Ioniq 6. No adb needed.
- **Energy model on two library builds:** Ioniq 5 US 260507 (`48ee793a...`) and
  Ioniq 6 US 260903 (`dbfe7bd2...`). On another build the recipes' verify regions do
  not match, nothing is written, sensor 23 is not offered and the sender does not
  start; fuel type, connectors and live data still go to Maps.
- **Preconditioning needs Maps' route data**, which only the Wireless CarPlay +
  Android Auto mod's library patch produces (as Leafia's did for the NEMESIS build).
- **No VIN in the log header** (HMG-API's rule). The product id stays.
- **Model list:** Ioniq 5 (NEEV, NEEV23, NEEV23SE; US; firmware 260507 or newer) and
  Ioniq 6 (CEEV; US 260903). The Ioniq 6 shares the 77.4-kWh E-GMP battery and charge
  baseline; live BMS energy learns its usable capacity. Until Ioniq 6 logs provide a
  dedicated fit, its road load uses the conservative Ioniq 5 calibration. A new
  firmware needs HMG-API's map plus a new recipe (`tools/mkrecipe.py --sites ...`)
  if its Android Auto library changed.

## 4. Checks run (off-car)

- `sh hmgapi-mod/test/run.sh`: egmp's PrecondTest 82, SenderRowTest 4, EvLogTest 34,
  ScaleTest 307 against the mod's classes; ModTest 19 (Precond → relay start / route /
  stop through the real PrecondCore, sensor 23, no-map behaviour); RelayTest 18
  (including: a stop is never sent for someone else's conditioning);
  HMG-API's script checker on the offload script; offload sandbox test 6; and
  `engine-test.sh`: the built mod through HMG-API's own engine on fake US Ioniq 5 units
  (NEEV23 and NEEV 260507, NEEV23 260903) and, when the checkout has its map, CEEV
  260903: no manifest errors, hooks load, every hook and framework point the mod uses
  is mapped, the shared GPM points co-exist with the Wireless mod, and each recipe
  parses and is clear of the Wireless recipe for the same library build.
- HMG-API patch: `core/runtime/commands-test.sh` 97/97.
- Not run: anything on a car; HMG-API's full `tools/test-all.sh` (needs clang/lld and
  qemu for its native parts, untouched by the patch).
