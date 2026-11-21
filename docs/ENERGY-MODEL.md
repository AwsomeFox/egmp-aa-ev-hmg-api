# Android Auto vehicle energy model: field map

Date: 2026-10-27, updated 2026-10-29 (v0.2.0). Schema recovered from **Google Maps 26.43.00** (`private/maps-26.43/`, jadx output). The only real sample is still aa-proxy-rs's `ford_ev_model.bin` (Ford F-150 Lightning capture, 9.5 KB), kept in `reference/`.

Files in `reference/`:
- `vehicle_energy_model.proto`: the recovered schema. Field numbers and types are exact. Since 2026-11-21 the names follow Google's own schema (see "Google's schema (VEM v1.2)" below).
- `lite_schema.py`: dumps a protobuf-lite message tree from jadx sources (`python3 reference/lite_schema.py private/maps-26.43/jadx/sources/defpackage cdim`).
- `pbdump.py` / `ford_ev_model.dump.txt`: the schema-less dump. **Note:** it shows `sint32` fields as raw varints, so the x axes in blocks 3 and 5 there are doubled.

## How Maps handles it (from the code)

- `SensorBatch` field 23 holds the bytes of a `cdim` message directly (`pxl.java:135` `cdim.parseFrom(sensor 23 bytes)`; `pxo.java` does the same through `CarSensorEvent`). Sensor 25 is a different, smaller message (`cdhl`).
- `gfz.q()` copies `cdim` into Maps' internal `cbjh` 1:1. There are **no unit conversions**; a few submessages are renumbered (table below). Maps adds `cbjh.12` itself (source info).
- On the phone, Maps reads only **current energy** and **capacity** (battery % and change metrics, `beps.aD`). Every other block goes to Google's servers unchanged (`qft` → `qgj`, and the `ckob`/`cnmk` request protos). **The energy prediction runs server-side**, so the code doesn't show what the tables mean. Meanings below come from Maps' own demo models plus physics.
- Gate (`qis.java`): if `1.3` current energy is **0**, Maps drops the model (or keeps the previous one if that was ≥ 5000 Wh, and logs "Battery level was 0 but previous battery level was >= 5000 Wh."). **Always send `1.3` > 0.**
- Re-route triggers (`qft.java`): a new model causes a refresh only if road-load A changes by ≥ 50, B by ≥ 5, C by ≥ 0.05, `6[0].1` by ≥ 600, `6[0].3` by ≥ 500, or `10.2` by ≥ 500. Unchanged blocks are dropped from the update sent to the server.
- Enum value names are gone (R8 turned the enums into ints), and no UI or debug strings describe the fields.

### Maps' built-in models (useful reference values)

| | `bdjr` "fake" | `qgr` "demo-vemlite" | `qgr` "demo-vemzero" | Ford sample |
|---|---|---|---|---|
| `1.2` mass kg (was read as reserve Wh) | 2000 | 2154 | 2501 | 3175 |
| `1.3` current Wh | 73800 | % × 78000 | caller value | 68378 |
| `1.4` capacity Wh | 82000 | 78000 | 65677 | 92403 |
| `1.9` | 170000 | 170000 | 240000 | - |
| `1.10` | 155000 | 155000 | 150000 | - |
| road load A / B / C | 1200 / 0 / 1e-10 | 138.38 / 2.18 / 0.3617 | - | 167.3 / 2.8 / 0.75 |

Every sample's `1.2` is 2.4–3.8% of its capacity, which first read as a reserve; Google's schema says it is the mass in kg. The vemlite road load fits a mid-size EV crossover in N, N/(m/s), N/(m/s)².

## Google's schema (VEM v1.2)

Google's own definition, labelled "Vehicle energy model (v1.2)", package `maps.automotive.vehicle_energy_model`, is public in [kangrio/CarService-AOSP `proto/energy_model.proto`](https://github.com/kangrio/CarService-AOSP/blob/main/proto/energy_model.proto). It points to a partner-only page (`docs.partner.android.com/gas/integrate/maps/maps_ev/vem_reference`). It matches the recovered layout in every block: same field numbers, wire types and nesting, including `3.7` (drivetrain losses at 7, 1-6 reserved), `5.4` (battery losses at 4, 1-3 reserved), `6` {1, 3} with 2 reserved, the five-part block 10, and `1.5` as a 3-value enum. That settles most of the "unknown" rows below (2026-11-21):

| Path | v1.2 name | Notes |
|---|---|---|
| `1.2` Q | `mass_kilograms` | **vehicle mass in kg**, not a Wh reserve. The demo values (2000-3175) are vehicle masses; the "~3% of capacity" was a coincidence. egmp sent 2400 until 0.8.x and sends 2200 now (2023 US Ioniq 5: 1,950-2,115 kg curb, plus a driver) |
| `1.5` enum | `hvac_status` | 0 unknown, 1 off, 2 on. **Live now**: on when climate power (HEV 0x4f) is ≥ 150 W, with 60 s hysteresis |
| `1.6` float | `reference_air_density` | |
| `1.8` float | `battery_temp_celsius` | "only required if battery preconditioning parameters are provided". **Live now**: the cluster packet's min pack temp (`XEV_MIN_BATT_TEMP`); 25 °C until the first packet |
| `1.9` uint32 | `peak_motor_power_watts` | "only required for lightweight predictions". **Sent by trim**: 168 kW (long-range RWD) until the front motor reports drive energy (HEV 0x47, AWD: 239 kW); 125 kW for a learned pack under 56 kWh (standard range) |
| `1.10` uint32 | `maximum_charging_rate_watts` | lightweight predictions. **Sent now**: 235 kW, the 4.1 peak |
| `1.11` bool | `heat_pump_equipped` | lightweight predictions. Trim-dependent; not sent |
| `1.12[]` | - | not in v1.2 (newer than the leak) |
| `4` | `max_regen_power` {1 at energy level, 2 at relative time} | **regen**, not charge, power. egmp fills 4.1 with the DC charge curve (the Ford sample has a charge-like curve there too). Car test: flatten 4.1 and see whether Maps' charge minutes move |
| `6[]` | `auxiliary_drain_at_time` {1 relative time s, 3 drain W} | future aux drain, held > 15 min, then Maps falls back to 7 |
| `7[]` Q2 / Q3 / Q4 | `hvac_drain_watts` / `battery_conditioning_drain_watts` / `total_drain_watts` | egmp fills the total, like Ford |
| `8.2[]` | `charging_curves_at_temperature` (battery temp) | "only required if battery preconditioning parameters are provided" |
| `9` | `current_charging_time` {target Wh, time remaining s} | only while plugged in |
| `10` | `battery_preconditioning` | {1 target pack temp by charger power, 2 conditioning power W, 3 extra Wh by target temp, 4 minimum battery Wh, 5 max temp rise at a station by charger power} |

## Field map (paths are inside `SensorBatch.23`)

Confidence: **code** = proven by Maps code. **strong** = units and values fit physics and the demo models. **guess** = plausible only. `Q` = `Quantity {1: int32 value, 2: float}`. Int values go in field 1. The demos also set field 2 = 0.0; Ford leaves it out.

| Path (type) | → `cbjh` | Ford value | Meaning | Confidence |
|---|---|---|---|---|
| `1.2` Q | 1.1.i | 3175 | vehicle mass, kg | Google schema (v1.2) |
| `1.3` Q | 1.1.d | 68378 | **current battery energy, Wh**. Must be > 0 | code |
| `1.4` Q | 1.1.e | 92403 | **capacity, Wh** (100% point for battery %) | code |
| `1.5` enum | 1.1.f | - | HVAC status: 0 unknown, 1 off, 2 on | Google schema (v1.2) |
| `1.6` float | 1.1.g | 1.293 | air density, kg/m³ | strong |
| `1.7` float | 1.1.h | 27.5 | outside temperature, °C | strong |
| `1.8` float | 1.1.m | - | battery temperature, °C | Google schema (v1.2) |
| `1.9` uint32 | 1.1.j | - | peak motor power, W (demos 170 kW / 240 kW) | Google schema (v1.2) |
| `1.10` uint32 | 1.1.k | - | maximum charging rate, W (demos 155 kW / 150 kW) | Google schema (v1.2) |
| `1.11` bool | 1.1.l | - | heat pump equipped | Google schema (v1.2) |
| `1.12[]` {enum(5), uint32} | 1.1.n | - | ? (could be per-connector limits) | - |
| `2.1.1` / `2.2.1` / `2.3.1` float | 2 | 167.3 / 2.8 / 0.75 | **road load A [N], B [N/(m/s)], C [N/(m/s)²]**. `x.2` float is never set | code (re-route thresholds) + strong |
| `3.7[]` {float speed m/s, `2[]` {**sint32** x, Q y}} | 10 | 10 rows × 50 pts | **drivetrain loss map**: x = wheel power 0..191498 W (step 3908), y = loss W. Efficiency at 100 kW goes from 74% (standstill) to 91% (36 m/s). Ford has no negative x (regen) points | strong |
| `3.8` uint32 | 10.d | - | ? | - |
| `4.1[]` {int32 Wh, uint32 W} | 4.b | 26 rows | Google schema: **max regen power vs battery energy**. Ford's curve (flat 207 kW, tapering above 105 kWh) read as a charge curve, and egmp sends its charge curve here | Google schema (v1.2); egmp's use untested |
| `4.2[]` {int32, uint32} | 4.c | - | max regen power vs relative time (s) | Google schema (v1.2) |
| `5.4[]` {**sint32** x, Q y} | 11 | 30 pts | **battery internal loss**: x = battery power 5..150 kW, y = loss W, exactly I²R with R ≈ 0.030 Ω at 400 V | strong |
| `6[]` {int32, Q at field 3} | 6 | - | future aux drain: relative time s, drain W. Changes to `[0]` trigger a re-route | Google schema (v1.2) |
| `7[]` {float °C, Q 2, Q 3, Q 4} | 7 | 15 rows, only Q4 | **aux drain vs outside temp**, W: Q2 HVAC, Q3 battery conditioning, Q4 total | Google schema (v1.2) |
| `8.1[]` {int32 charger W, `2[]` {int32 Wh, int32 s}} | 8.b | 9 tables | **charge-time tables per charger power**. Each point is (**Wh still needed to reach full**, **seconds to full** from there), in descending headroom, no (0, 0) point. See "8.1 semantics" below | strong (DC: reproduces Maps' figures); AC rows uncertain |
| `8.2[]` {float, `2[]` as 8.1} | 8.c | - | the same tables keyed by battery temperature | Google schema (v1.2) |
| `9` {int32, Q at field 2} | 9 | empty | charging time while plugged in: target Wh, time remaining s | Google schema (v1.2) |
| `10` {UF[], u32, FU[], u32, UF[]} | 13 | - | battery preconditioning (see the v1.2 table). `10.2` (conditioning power) triggers re-routes | Google schema (v1.2) |

## What an Ioniq 5 model needs

Write it as protobuf text (`vem.VehicleEnergyModel`), then `protoc --encode=vem.VehicleEnergyModel reference/vehicle_energy_model.proto`, then wrap it as SensorBatch field 23 (tag bytes `ba 01` + length). Re-encoding the Ford sample this way reproduces it byte for byte.

| Block | Source |
|---|---|
| `1.3` / `1.4` | `1.3` = BMS remaining energy (HEV 0x31 `ENERGYMAP` × 2 Wh), live. `1.4` = usable capacity learned on the car as that energy ÷ display SOC (~62.9 kWh on the owner's pack); 77.4 kWh (2022 US LR) until a sample is learned. See "Capacity scaling" |
| `1.2` | vehicle mass, 2200 kg (2400 until 0.8.x, when it was read as a reserve) |
| `1.5`, `1.8` | live: HVAC on/off from climate power (HEV 0x4f ≥ 150 W, 60 s hysteresis); pack temp from the cluster's battery packet (`XEV_MIN_BATT_TEMP`), 25 °C until known |
| `1.10` | 235 kW, the 4.1 peak |
| `1.6`, `1.7` | 1.225 static density; **outside temp is live** (nav battery packet `DATC_OUT_TEMP_SNSR` via `NavigationCluster.requestBatteryInfoReq()`, patched in place at runtime) |
| `2.x` road load | **your rolldown data** (conversions below) |
| `3.7` loss map | not in rolldown. Scale Ford's map, or build one from an efficiency estimate. Remember x is **sint32** |
| `4.1` | E-GMP 800 V curve. Bulk (≤80%) calibrated on 233 real EA sessions, warm/preconditioned pack: ~230–235 kW (3C) to 50%, thermal step-down at 53% to 135 kW held to 80% (160 kW until 2026-11-17; see "Field findings (2026-11-17)"). Top end (>80%) is **the car's own charge-time estimate**, which the owner confirms is realistic: 70/48.5/33.5/25.5/17/12 kW at 81/85/90/95/98/100%. That gives **80→90% = 8 min and 90→100% = 18 min** on a 350 kW charger at the learned 62.9 kWh (~50 kW average over 80–90%, ~22 kW over 90–100%), the same on every charger above ~60 kW. No thermal pause. (Cold-pack caps were measured but aren't shipped; see "field findings".) The energy axis is defined for the 77.4 kWh reference pack and **rescaled at runtime to the learned capacity** (see "Capacity scaling") |
| `5.4` | I²R: loss = R·(P/V)². Use the pack's DC resistance and ~650–700 V nominal. R is unmeasured; estimate it from the cell count and cell resistance, or from the voltage sag under load |
| `7` aux power (Q4) | driving heater/A-C power vs outside temp; cold part calibrated with road load A (see "Cold weather") |
| `8.1` | integrated from 4.1 (×η=0.95 charger→battery, 0.5% cells in 4 slices) + AC limits (10.9 kW onboard, 1.44 kW L1); 16 charger rows (350/330/325/320/260/250/240/200/175/150/125/100/50/25 kW DC, 10.9/1.44 kW AC). Each row has 29 points of (headroom to full, seconds to full): headroom from 100% down to 10% in 5% steps, then 9…1% in 1% steps, then 0.5%. **250 and 325 kW = Tesla Superchargers** (see "Tesla Superchargers" below): V3 cabinets (listed at 250 kW) and V4 posts on upgraded V3 cabinets (listed at 325 kW) run at ~500 V, so the 800 V pack charges via the boost converter at a flat 97 kW to 90% (no step-down or pause), then the normal taper. Maps keys these tables by charger power only, so the voltage limit has to live in those rows; 260/240 and 330/320 kW rows bracket them so other stations near 250 or 325 kW stay on the 800 V profile. 500 kW V4 cabinets are above the 350 kW row and charge at full speed. Energy axis and seconds defined for the 77.4 kWh reference pack and **rescaled at runtime** (see "Capacity scaling") |
| leave out | `1.11` (trim-dependent), `1.12`, 9 (Ford works without them); 6, 8.2 and 10 only with the `winter_model` test setting (see "Winter add-on") |

### Unit conversion for EPA-style rolldown (target) coefficients
- A [N] = A[lbf] × 4.44822
- B [N/(m/s)] = B[lbf/mph] × 9.9506
- C [N/(m/s)²] = C[lbf/mph²] × 22.2592

If the data is your own coast-down (speed vs time), fit F(v) = m_eff·(-dv/dt) = A + B·v + C·v², with m_eff ≈ test mass × 1.03 to account for rotating inertia.

## Rolldown data (`reference/IONIQ5 roll-down test.xlsx`)

2022 Ioniq 5 SEL AWD, 2120 kg as tested, 61 °F, 40 psi, dirty. Two coast-downs in opposite directions, logged at 1 Hz:
- run 1: 51.9 → 26.1 m/s over 68 s
- run 2: 48.5 → 21.7 m/s over 74 s

The units really are m/s. Fitting the data as m/s gives CdA ≈ 0.82 m² and Crr ≈ 0.0094, which is right for an Ioniq 5 (Cd 0.288). Reading it as mph or km/h gives impossible values (CdA 1.8–3.0, Crr 0.003–0.004).

### Road load (block 2)
- **Recommended: A = 196 N, B = 0, C = 0.493 N/(m/s)².** This is one fit of F = m_eff·(−dv/dt) over both runs together, with m_eff = 2120 × 1.03 for rotating inertia. Fitted separately, the two runs give A = 143 / 189 and C = 0.492 / 0.545.
- B can't be fitted. All the data is above 21.7 m/s (48 mph), so A and B trade off against each other: a free 3-term fit gives A < 0. Setting B = 0 and letting A carry rolling resistance is the standard fix.
- The sheet's own route (separate cubic fits of power, then averaged) works out to A ≈ −0.7 N, B = 6.84, C = 0.460. It agrees with the physical fit from 60 mph up (within 1 kW). Below about 50 mph it's lower (1.5 vs 2.9 kW at 25 mph) because it has no rolling-resistance term. That's why the sheet's low-speed ranges (e.g. 1116 mi at 23 mph) are too optimistic.
- To pin A down, do a low-speed coast-down (about 45 → 5 mph, both directions).

### Aux / HVAC (block 7)
The shipped table is `AUX_TABLE` in the generator (see "Cold weather" below). The original reasoning: 250 / 500 / 1500 W are realistic for mild / typical / about 10 °F. As a starting `7[]` table (Q4 = W): about 1500 W at −12 °C, ramping to about 250 W at 18–24 °C, then back up to about 500–800 W at 35–45 °C for A/C. Ford's Q4 values are much larger (2.6–5.4 kW), so we don't know yet whether Q4 is only HVAC/accessories. Test on the car and compare predicted vs real consumption.

### Cold weather (blocks 2 and 7, live outside temp)
The warm model (rolldown road load, ~400 W aux) gave ~293 Wh/mi for a 240 mi winter highway trip at 26 °F, so Maps planned 100 → 9 % with no stop. Owner anchors (highway, trip computer):

| Outside | Speed | Real | Warm model |
|---|---|---|---|
| −12 °F (−24.4 °C) | 60 mph | ~1.6 mi/kWh (625 Wh/mi) | 285 Wh/mi without heat |
| 20 °F (−6.7 °C) | 65 mph | ~2.3 mi/kWh (435 Wh/mi) | 314 Wh/mi without heat |

All of that gap as heater power (block 7, time-based) would be ~20 kW at −24 °C and absurd at city speeds, so it's split:
- **Block 7 (time-based)**: driving heater power, assumed 3.0 kW at −6.7 °C and 6.5 kW at −24.4 °C, 500 W at 10 °C, ~400 W electronics floor when mild, A/C above 24 °C. Maps looks it up server-side by `1.7`, which the sender patches from the live outside temp.
- **Road load A (distance-based)**: cold tyres, denser air, drivetrain and battery losses. `COLD_DELTA_A` is solved so the anchors come out exact: +156 N at −6.7 °C, +483 N at −24.4 °C, 0 at 10 °C and above, linear between, clamped at −35 °C (+679 N). `EvModelSender` patches A (float at `ROAD_A_OFFSET`) from the live outside temp, with 3 °C hysteresis because Maps re-routes when A moves ≥ 50 N. Unknown temp keeps the last value, or the warm A.

Result at 26 °F: ~2.6–2.75 mi/kWh at 55–60 mph (was ~3.4), so that trip now needs ~90 kWh and gets a charging stop. Mild weather is unchanged (3.1 mi/kWh at 65 mph, 50–70 °F). The heater split is an assumption; more cold trips (mi/kWh, average speed, climate share from the energy screen) at different temperatures would pin it down. Outside temp is decoded as raw × 0.5 − 40 °C (Hyundai's own DATC decode, [DATA-SOURCES.md](DATA-SOURCES.md) §2), confirmed against the dash on 2026-10-29 (48 °F); raw 0xFE/0xFF is invalid, and so is raw 0 (−40 °C): the packet holds 0 at every boot until the sensor is read.

### Drivetrain efficiency (blocks 3 and 5)
The sheet uses a flat 0.9, battery to wheel. In the Maps model that's split between block 3 (drivetrain loss vs speed and wheel power) and block 5 (battery I²R). Don't put 10% in both. Suggested start: block 3 ≈ 7–8% of wheel power plus a few hundred W of fixed spin/inverter loss that grows with speed (Ford's map has 450 W at 0 m/s rising to 2.7 kW at 36 m/s, with zero wheel power), and block 5 with R ≈ 0.1 Ω at ~650 V. Both should then be calibrated so the total matches about 0.9 at highway load.

### 8.1 semantics: headroom and time to full (2026-10-29)
Google's server reads each 8.1 point as (**Wh still needed to reach full**, **seconds to full**), not as (absolute energy, seconds from empty). Ford's DC rows show this. The 150 kW row runs (99842 Wh, 5406 s) … (525 Wh, 82 s), descending, with no (0, 0) point. Read as headroom, the last 525 Wh take 82 s (~23 kW, a taper) and the first 5 kWh of headroom charge at ~135 kW. Read as energy from empty, it would charge at 134 kW near full and 24 kW from empty, which is backwards.

Until 2026-10-29 we emitted (absolute energy, cumulative seconds from empty), so the server used our fast low-SOC bulk as the last stretch to 100%. Reading the old tables the server's way reproduces what Maps showed (180 kW = the 175 kW row):

| Case (old tables) | Maps showed | Old tables read as headroom → time to full |
|---|---|---|
| 17→100%, capacity 62.5 kWh, 180 kW | 19 min | 19.1 min |
| 21→100%, capacity 62.5 kWh, 180 kW | 18 min | 18.1 min |
| 28→100%, capacity 62.9 kWh, unscaled, 180 kW | 16 min | 16.5 min |

The generator now emits `charge_table()` = (C − E, T(C) − T(E)) in descending headroom, from the same integration as before (`cumulative_table()`, which the analysis and plot tools use). Time from a% to b% is T(cap·(1−a)) − T(cap·(1−b)). Both values still scale by k at runtime. The AA message was 5444 bytes with 13 rows; with the 325 kW Supercharger row and its brackets (16 rows × 29 points) it is 6335 bytes (Ford's sample is 9.5 KB).

**Uncertain: AC rows.** Ford's AC rows look different: (0 Wh, 363600 s) … (100000 Wh, 3600 s), (101000 Wh, 0 s). That's ascending energy and descending time, so they read as (absolute energy, time to full). Read as headroom they make no sense (0 Wh to go would take 101 h). Either Ford generated its AC rows wrong or the server treats AC rows differently. Our AC rows use the DC headroom convention too. Maps rarely plans AC stops, so check this if it ever shows an AC charging time.

### DC charging (blocks 4.1 and 8.1)
The sheet's 10→X% averages (alexonautos test) work out to these averages per band:

| SOC band | avg kW |
|---|---|
| 10–50% | 214 |
| 50–60% | 200 |
| 60–70% | 180 |
| 70–80% | 155 |
| 80–90% | 115 |
| 90–100% | 99 ⚠ |

The 90–100% value comes from the sheet's 165 kW / 25 min for 10–100%. That's too fast: real E-GMP charging tapers to about 30–50 kW above 90%. Use a measured curve there.

For 4.1, x = absolute energy in Wh (SOC × 77400 for the reference pack; × the learned capacity on the car, see below). These are band averages, so the peak inside the 10–50% band is higher (about 230 kW).

### Capacity scaling (4.1 and 8.1 follow the learned capacity)
The head unit sends the **learned** usable capacity in `1.4` (~62.9 kWh on the owner's car) and the BMS energy in `1.3`. Maps reads the curve and tables in absolute Wh, so with an unscaled 77.4 kWh axis "100%" (62.9 kWh) sat at ~81% of the reference curve, before almost all of the taper: on the car (2026-10-29) Maps showed **28→100% in 16 min on a 180 kW charger**. Integrating the unscaled 4.1 curve over 17.6→62.9 kWh at 180 kW gives exactly 16.0 min; the unscaled 8.1 tables give 17.1/17.4 min on the 175/200 kW rows.

Charging limits are SOC-based (the BMS tapers by cell voltage), so `EvModelSender.scaleTemplate` multiplies every 4.1 `energy_wh`, every 8.1 `energy_wh` and every 8.1 `seconds` by k = capacity / 77400 (same power at the same SOC, k × the energy per segment). Powers (4.1 `power_w`, 8.1 `charger_power_w`) are untouched, and the Supercharger rows stay flat to 90% SOC. (The 300 s thermal pause and its unscaled `TIME_FIXED_S` part were removed along with the old top end.) The generator widens those varints to 3 bytes (`SCALE_WIDTH`, length prefixes rebuilt; `protoc --decode` of the widened model must equal the canonical encoding) and emits their offsets and reference values into `EvModelTemplate` (`AXIS_*`, `TIME_*`). The sender re-scales only when the capacity changes (cached), inside the tick's catch-all; on failure it sends the unscaled template. Widening grew the AA message from 4045 to 4394 bytes, the 29-point headroom rows took it to 5444, and 16 rows take it to 6335. Host test: `gpm-ev/test/ScaleTest.java`.

Minutes from the tables at 62.9 kWh (k = 0.813); 180 kW interpolates the 175 and 200 kW rows. "2022-23 top end" is the scaled model before the top end was recalibrated (300 s pause included, tables read as intended). "Now" is the shipped model:

| | 10→80% | 44→80% | 80→90% | 90→100% | 21→100% | 28→100% |
|---|---|---|---|---|---|---|
| 180 kW, 2022-23 top end | 16.2 | 8.7 | 4.6 | 12.5 | 30.9 | 29.4 |
| 180 kW, 160 kW plateau (to 2026-11-17) | 16.2 | 8.7 | 8.0 | 18.0 | 39.8 | 38.2 |
| 180 kW, now (135 kW plateau) | 17.5 | 10.0 | 8.0 | 18.0 | 41.1 | 39.5 |
| 350 kW, 2022-23 top end | 14.2 | 8.4 | 8.6 | 12.5 | 33.5 | 32.3 |
| 350 kW, 160 kW plateau (to 2026-11-17) | 14.2 | 8.4 | 8.0 | 18.0 | 38.3 | 37.1 |
| 350 kW, now (135 kW plateau) | 15.5 | 9.7 | 8.0 | 18.0 | 39.6 | 38.4 |
| 350 kW, now, 77.4 kWh reference pack | 19.0 | 11.9 | 9.9 | 22.1 | 48.7 | 47.3 |

**Open question (capacity):** recent EA sessions suggest ~70–71 kWh usable, not 62.9 (several 2024-26 deep sessions delivered more energy than 62.9 kWh allows, see "Field findings"). Either the BMS energy reads low at high SOC or its 2 Wh unit is off. A logged DC charge (ΔENERGYMAP × 2 vs charger kWh × 0.95) will settle it; until then the shipped capacity is the BMS figure. *(2026-11-17: AC sessions on a 2022 EV6 point to the 2 Wh unit being right. Over 2–4 h on a 10.9 kW-class circuit, ΔENERGYMAP × 2 gave 9.3–9.7 kW into the battery. Energy reading 13 % low would mean ~11 kW, more than the 10.9 kW on-board charger can deliver. See "Field findings (2026-11-17)".)*

Scaled times are shorter than a new pack's because a smaller pack takes in less energy at the same power. Unconfirmed: whether Maps' server uses 4.1, 8.1 or both. Both are scaled, and the tables match a fine integration of 4.1.

### Tesla Superchargers (2026-11-11)
Maps keys the 8.1 tables by the station's listed power only. It doesn't pass the network, connector or voltage, so the only way to model a Supercharger is a row at the power Maps lists it at. The listings and what a pre-2025 E-GMP car (800 V pack, CCS1 port with the NACS adapter) gets from each:

| Maps lists | Hardware | Output voltage | E-GMP (pre-2025) | 8.1 row |
|---|---|---|---|---|
| 250 kW | V3 cabinet (V3 posts, or V4 posts on a V3 cabinet) | ~500 V | boost converter, ~97 kW peak | boost-limited |
| 325 kW | V4 posts on an upgraded V3 cabinet ("V3.5"); 325 kW is Cybertruck-only | ~500 V for us | boost converter, same as 250 kW (owner-observed) | boost-limited |
| 500 kW | V4 cabinet | up to 1000 V | full 800 V curve | above the 350 kW row (full speed) |

- **Flat power: 97 kW** charger-side to 90%, then the normal taper. The first fit used one owner's 105 kW. Other owners report 96–97 kW as the most a pre-2025 E-GMP car gets on a Supercharger, so the row uses the lower figure. It's conservative, and a pessimistic Tesla row also steers Maps toward 800 V stations, which are much faster for this car. 10→80% on the reference pack is 35.3 min on these rows (was 32.6 at 105 kW) vs 19.0 on 350 kW. At the learned 62.9 kWh it is 28.7 vs 15.5 min. The 2025+ cars (native NACS, new pack) are quoted at up to ~125–135 kW and need their own model.
- **Brackets.** 320/330 and 240/260 kW rows carry the normal 800 V curve, so a non-Tesla station listed near 325 or 250 kW gets the full-speed curve whether Maps snaps to the nearest row or interpolates. The cost is 3 rows (~0.9 KB).
- **What it can't do.** Any non-Tesla station listed at exactly 250 or 325 kW is treated as a Supercharger. A 500 V V3 site listed at another power (e.g. an old 150 kW V2 site) gets that power's 800 V row. Rows are only matched by power.
- **Long shot: `1.12`.** `1.12[]` is `{enum, uint32}`, and the enum has only 5 values (0–4). That rules out `EvConnectorType` (0–9 and 101, TESLA_SUPERCHARGER = 8), so it is not a per-connector power limit. It might be a limit per charging type (AC/DC levels) or something else entirely. Nothing so far suggests Maps can tell a Supercharger from another station except by listed power. Cheap car test: send `{k, 97000}` for each k in 0–4 and see whether any changes Maps' DC charge times.

## Field findings (2026-10-28, from the car and 233 real EA sessions)

Calibration (`tools/analyze_charge_history.py`, `reference/charge_history*.csv`, plus exact start-SOC spot pulls from the EA app):
- **EA "TotalEnergyDelivered" is charger-side**; the battery absorbs ~5% less. Warm-weather η = 0.944–0.958 → `CHARGE_ETA = 0.95` in the time tables. Mean model error vs 233 sessions (`tools/analyze_charge_history.py`, all at C = 77.4 kWh): −2.0 min, MAE 3.1 min with the 2022-23 top end; −1.3 min, MAE 2.7 min now.
- **Usable capacity ≈ 77.4 kWh when new** (2022 deep sessions fit C=77400 with η≈0.95) — Hyundai's figure is usable, not gross. Runtime degradation: usable capacity is learned on the car as BMS available energy ÷ display SOC. `EV_AVLBL_ENRG` (nav battery packet) is in 2 Wh steps like `BMS_EVENERGYCAPA`: 14462 at 46.0% on the owner's ~105k mi car = 28.9 kWh → **~62.9 kWh usable**, vs 77.4 nominal. BMS SOH (`tm_hscan_service` txn 0xd6, reads 95) is a health index (resistance, balance), not usable capacity; it is only logged.
- **The thermal step-down depends on the session (heat added this session), not on absolute SOC.** The car charges at full 3C from 0% (no low-SOC ramp). It steps down from 3C to ~160 kW at ~55–60% from a 0% start, ~65–70% from 10%, and ~75–80% from 30%. The energy-indexed model can't express that, so the step is placed for the usual road-trip case of arriving at the charger low: at 53%. That gave **10→80% = 17.5 min** on 350 kW on the reference pack with a 160 kW plateau (the owner's real figure; Hyundai's official figure is 18); with today's 135 kW it is 19.0. Sessions that arrive warm or at high SOC will read slightly pessimistic in the mid band, and slightly optimistic otherwise. Some 2022 sessions showed a 3–6 min thermal pause at ~78–85%. It is no longer modelled separately (see the top end below).
- **Top end (>80%) = the car's own charge-time estimate.** On 2026-10-29, on a 350 kW charger from 44%, the car predicted 80% at 21 min, 90% at 29 and 100% at 47: **80→90% = 8 min, 90→100% = 18 min**. The owner confirms that's realistic for this car. Charging that high rarely makes sense, and a realistically slow taper is what makes Maps plan departures well below 100%. The pack was cold that night (min cell 10 °C), but that mainly slows the bulk; 80–100% is voltage-limited. The curve is solved so both anchors come out exactly at the learned 62.9 kWh, and `ScaleTest` asserts them. This replaces the 2022-23 fit (150 kW after a 300 s pause at ~81%, then 60/35/22/15 kW at 90/95/98/100%). That fit reached 80→90% = 8.6 min only because of the pause, and gave 90→100% = 12.5 min. Its "95→100% in 10.4 min" spot check is superseded.
- **The EA history agrees with the new top end.** Start SOC is backed out as end − η·E/C. For 2022-23, C = 77.4 kWh. For 2024-26, the learned 62.9 kWh gives impossible negative starts for several deep sessions (up to 74 kWh delivered ending at 100%), so 71 kWh, the 2024-26 envelope, was checked too. Taking sessions on 350 kW-class cabinets that start at ≤60% and end at ≥88%, the time beyond the modelled bulk per SOC% above 80 (normalised to 62.9 kWh) is: 2022-23 median **0.97 min/%** (n=23), 2024-26 0.79–0.94 min/% (n=5–9 depending on capacity). The new model's 0.8 min/% for 80–90% and 1.8 min/% for 90–100% fit that, since most sessions stop before 95%. The top end was no faster in 2022-23; the old curve was simply too fast there. Model vs sessions ending at ≥85%: 2022-23 mean error −4.2 → −1.3 min, MAE 5.1 → 3.6 (n=64). 2024-26 MAE 3.0 → 1.5 at C=62.9 (n=13) and 4.7 → 3.6 at C=71 (n=21).
- **In 2024-26 sessions the bulk is slower than the warm model**: median actual/model 1.2–1.3, vs 1.11 in 2022-23. Many recent sessions were not preconditioned. The bulk stayed warm-pack because preconditioning is now verified working on the car. A preconditioned 2022 EV6 still only held ~125 kW (2026-11-17), so the plateau is now 135 kW.
- **EA export quirks**: some 2022 sessions have `ChargeDuration = 0 min 0 sec` (use `TotalDuration − GracePeriod`); one negative-duration row; one hour-format duration.
- **Derated/broken cabinets** are obvious by max rate (e.g. 45/54/74 kW on 350-class stations). A cluster of ~104–115 kW maxes = 400 V-class cabinets or shared power — the car's boost converter caps ~100 kW on those (same as Tesla 500 V Superchargers; a route planner can't see this from charger rating alone).
- **Cold-pack caps** (owner's logbook, *not shipped* — the shipped model assumes preconditioning works): <20 °C → ~160 kW, <15 °C → ~120 kW, <10 °C → ~90 kW.

Shipped-model decisions from this:
- **Single warm-pack curve.** For road-trip planning the car preconditions en route to a charger, so the ideal-temp curve is the realistic one. Multi-variant temp-swap machinery was built, evaluated, and stripped (see below).
- **Live outside temp** (nav battery packet `DATC_OUT_TEMP_SNSR` via `NavigationCluster.requestBatteryInfoReq()`) patches field 1.7, so the block-7 aux table does real work in winter/summer.

Explored and parked:
- **Temp-keyed charge tables (8.2 `by_temp`)**: implemented, then removed. The semantics are unconfirmed, and there's a structural hole: the planner can't pick a per-stop temperature without knowing pack temp and its evolution — no pack-temp field exists in the sensor-23 schema, so any 8.2 consumer needs its own thermal model. (Correction, 2026-11-21: Google's v1.2 schema has one, `1.8` `battery_temp_celsius`, which egmp now sends live; 8.2 and block 10 would build on it.) Possible real channel for pack temp: **sensor 25 (`cdhl`)** — schema `cdig{1: int32, 2: int32, 4: repeated {3: float, 4: float}}`, unconfirmed; Ford works without it. The self-correcting mechanism (re-send every 5 s, variant/curve always reflects current pack) is the robust path.
- **Pack-temp variant swap with hysteresis**: worked in code, but "current temp" is the wrong state for planning a stop an hour away (preconditioning). Stripped per above.
- **Nav battery packet layout** (`BATTERY_INFO_RX`, 11 bytes): [0]=SOC in 0.5% steps ✓, [1]=min cell temp °C ✓, [2..3]=`EV_AVLBL_ENRG` big-endian uint16 tracking SOC (~7202 units at 24% — units unconfirmed; 2 Wh ⇒ 60 kWh full, conflicts with 77.4; maybe power, maybe display-buffered SOC), [6]=`VCU_DTE_BASE` 76 ≈ cluster km, [9]/[10] vary (status?). Pin units down with a drive/charge capture where SOC moves. *(Resolved, see [DATA-SOURCES.md](DATA-SOURCES.md) §1.1: [2..3] is the BMS remaining usable energy in 2 Wh steps, already degraded, so the "conflict" with 77.4 was the real capacity loss; byte 6 is the outside temperature and DTE is bytes 8–9.)*
- **Regen (negative-x) drivetrain points**: shipped as an experiment (sint32 allows it, Ford omits it); validate downhill predictions on a hilly drive.

## Battery % curve (sensor 26, 2026-11-10)

Maps doesn't take the car's state of charge from sensor 23: it shows **energy ÷ capacity** (`1.3` / `1.4`). On E-GMP the BMS energy (`ENERGYMAP`) reaches 0 near **display 2.3 %**, so Maps read up to 2 points under the dash, most near empty, where its arrival % and 0 % checks matter. Maps has a second sensor for exactly this.

**From the code (Google Maps 26.44.06):**
- The car-sensor dispatcher (`pyi`) registers handlers for its sensor types 1, 2, 6, 7, 10, 11, 14, 17, 18, 23, 24 and 25. Type 23 is parsed straight from the bytes as the energy model; 25 is decoded by `CarSensorEvent.c()` as `cdpz`:
  ```
  cdpz { 1: cdqu { 1: int32, 2: int32, 4: repeated cdqt { 3: float raw %, 4: float shown % } } }
  ```
- `avqa` keeps the points only if 0 < raw < 100, 0 ≤ shown ≤ 100, raw rising and shown not falling (`avqa` doesn't read fields 1 and 2).
- **Fields 1 and 2 are the EV trip settings** (Maps 26.44, 2026-11-21): `awfg.b()` returns `cdqu.1` and `awfg.c()` returns `cdqu.2` when they are > 0 (and a projected-car check, `akvk.E().b()`, passes), ahead of the user's own vehicle-profile values and a server default. The profile class (`bdpt`) names them in its `toString`: **1 = `minimumArrivalBatteryPercentageForChargingStops`**, **2 = `targetArrivalBatteryPercentageForDestination`**. So the head unit can set Maps' "arrive at chargers with at least X %" and "arrive at the destination with Y %". egmp sends neither (0, not set), so Maps uses the user's settings. Debug overrides (`azqv.bO`/`bP`) come first. A mod setting for these is possible but untested on the car.
- `ayzm.K(energy, capacity, curve)` takes raw % = energy × 100 ÷ capacity and maps it through the points, linear between them, with (0, 0) and (100, 100) assumed at the ends. Every battery percent Maps shows goes through it: the current level, arrival at stops and the destination, and its "arrives at 0 %" checks.

**On the wire it's field 26, wrapped.** Maps' sensor numbers aren't Android Auto's (Maps' 11, driving status, is the wire's 13). Tested on a US Ioniq 5 with a curve that adds 20 points, so the effect is obvious in Maps:

| SensorBatch field, contents | Maps' battery % |
|---|---|
| 25, `cdpz` | unchanged |
| 25, `{1: cdpz}` | unchanged |
| **26, `{1: cdpz}`** | **20 points higher** |

Field 26 is what Open Android Auto calls `RAW_EV_TRIP_SETTINGS {1: bytes}`; the phone asks for it as soon as the head unit offers it. The Android Auto library only parses requests for types it accepts, so the recipe raises `SensorType_IsValid` to 26 (`cmp r0,#25`, `tools/mkrecipe.py --max-type 26`); with only 23 accepted, a request for 26 logs `Failed to parse proto … SensorSource.cpp:209`.

**What the mod sends** (`SocCurve`, every 5 s after the model, as its own `0x8003` message): 13 points (raw 1, 5, 10, 20 … 90, 95, 99 %) on the line through (raw 0, shown 2 %) and the car's current (energy ÷ capacity, display SOC), clamped to 0–100 and never falling. It's offered only where the recipe in memory takes type 26 (`NativeCheck.maxSensorType`): the US 260507 build today. The Ioniq 6 US 260903 recipe takes 26 too since 2026-11-21: its 16-byte window there has the same md5 as the US build's, so the new verify md5 was computed from those bytes (not yet checked on an Ioniq 6). Setting `battery_curve`. The same message carries the arrival targets when `arrival_charger_pct` / `arrival_dest_pct` are above 0 (cdqu fields 1 and 2, varints); with `battery_curve` off but a target set, sensor 26 still goes out, without points.

**Zero point:** `EvModelTemplate.ENERGY_ZERO_PCT` = 2.3 %, from parked log rows (energy and display SOC together): a 2022 EV6 fits 649 Wh/% × (SOC − 2.32 %) from 7 to 100 %, a 2023 Ioniq 5 730 Wh/% × (SOC − 2.27 %) from 41 to 100 % (`tools/analyze_drive_logs.py capacity`). It was 2 % until 2026-11-17.

## Field findings (2026-11-17, a 2022 EV6's logs)

Issue #13 (AwsomeFox/egmp-aa-ev-hmg-api): a 2022 EV6 AWD, US 260507, ~90k mi. 28 logs over a week: ~1000 km, mostly interstate at 105–130 km/h, 5–28 °C. The two long days were about 1000 ft down and back up. The logs include three DC stops and four AC sessions. `tools/analyze_drive_logs.py` reproduces every number here: `summary`, `charges`, `drive` and `capacity` on the log files.

**Road load per model.** On 84 steady 10 km stretches, with the logged climate power taken out, the Ioniq 5 road load predicted 234 Wh/km at 120 km/h. The EV6 used ~193.
- Over all stretches, the model came out 22 % high on energy.
- Maps' own plan agrees: at the 350 kW stop it expected 42.3 % on arrival, and the car arrived at 45.6 %.
- Highway data can't separate A from C. Any C from 0.35 to 0.45 with its fitted A matches within ~3 Wh/km.
- The EV6 now gets **A = 180 N, C = 0.40 N/(m/s)²** (`CAR_ROAD_LOAD`). That is CdA ~0.65 m² against the Ioniq 5's ~0.80, and rolling resistance ~0.0085. With it, the model comes out 4 % high on the same stretches.
- The sender picks the pair once per session from `ro.build.product` (`EvModelSender.carIndex`) and patches it into 2.1.1 (A, plus the cold penalty) and 2.3.1 (C, `ROAD_C_OFFSET`).
- Other models keep the Ioniq 5 values until their logs say otherwise. The Ioniq 6 is the obvious next one: it is much sleeker, but there are no logs yet.

Not changed:
- **Stop-and-go city driving:** it uses ~20 % more than the steady-speed model on both the EV6 and a 2023 Ioniq 5's city logs. Maps plans that part itself.
- **Hills:** they cost ~4.8 Wh per metre of GPS altitude. Hills are Maps' server's job. (It has a mass field after all: `1.2`, see "Google's schema (VEM v1.2)".)

**DC plateau 160 → 135 kW (53–80 %).**
- After a 2–3 min ramp-up, the EV6 held 120–125 kW battery-side from 45 to 80 % on a 350 kW charger, with the pack at 20–28 °C.
- The car's own preconditioning had run (state DONE) and stopped at ~22 °C, with 8 °C outside.
- 46 → 83 % took 12.8 min. The 160 kW model said 9.4, Maps planned 10.0, and 135 kW says 10.7.
- The owner's 2024-26 EA sessions ran 1.2–1.3× the 160 kW model too.
- The taper from 80 % matched.
- No session started below 38 %, so the 230 kW bulk below 50 % is untested and unchanged.
- 10 → 80 % on the reference pack is now 19.0 min. It was 17.4, against Hyundai's 18 for a fully warm pack.

**AC.** The EV6 put 9.3–9.7 kW into the battery on a 10.9 kW-class circuit and 5.6–5.7 kW on a ~7 kW one. The AC rows now use an efficiency of 0.89 (`AC_ETA`) instead of 0.95. 40 → 99.5 % at 10.9 kW: the model now says 236 min, against 249 real.

**Capacity learning.**
- Energy reaches 0 at display ~2.3 % (see "Battery % curve").
- The old estimate, energy ÷ SOC, read 1.6 % low at 60 % and 7 % low at 30 %. The EV6's learned capacity swung from 61.5 to 64.6 kWh between sessions.
- A sample is now energy × (100 − 2.3) ÷ (SOC − 2.3): the energy at 100 % display. That is 63.4 kWh on the EV6, 82 % of 77.4. The car's own health index reads 92–93 %, so it is not usable capacity.
- The BMS energy dropped 1.6 kWh at ~13 % while parked (a recalibration). Low-SOC readings can jump.

## On-car learning (road load)

`RoadLearn` (gpm-ev/src, both builds) learns one number per car: a scale on the model's wheel power, A and C together.
- **Input:** EvDriveLog hands it each 5 s log row, so it needs EV logs on.
- **Stretches:** a stretch is 10 km of driving with no charging and no gap over 30 s. One at an average of 60 km/h or more gives a sample: (energy used − drivetrain spin − measured climate) ÷ (the model's wheel power × 1.06).
- **Speed:** the model's wheel power uses the logged speed scaled to the odometer distance. The display speed reads a few percent high, and v³ would carry that into the scale.
- **Rejected samples:** a sample outside 0.4–2.0 is a bad stretch, such as a BMS energy jump, and is dropped.
- **Scale:** (20 + sum of samples) ÷ (20 + n), clamped to 0.7–1.3. The shipped model counts as 20 stretches at 1.0.
- **Storage:** kept in GPM's files dir as `road_scale`.
- **Use:** the sender reads it once per Android Auto session and sends A × scale (cold penalty included) and C × scale, so a route never re-plans because of it.
- **Visibility:** the log header shows `road_scale` and `road_scale_n`.
- **Setting:** `model_learning`; turning it off deletes the file.

Why one number: highway data can't separate A from C (see "Field findings (2026-11-17)"), and one scale is stable after a few stretches.

Altitude isn't used. GPS altitude is often missing, and filtering out hilly stretches halved the data for no gain. Up and down cancel over many stretches.

Replaying the 2022 EV6's logs (`EV_LOGS=<folder> sh gpm-ev/test/run.sh`):
- **From the Ioniq 5 values:** the scale settled at 0.84–0.89 after the first long day. The second day's highway energy went from +10 % to −1 % off.
- **From the EV6's own values:** it stays at 1.006 after 78 stretches, so learning doesn't fight the per-model defaults.
- **City drives** never qualify.

Not learned yet:
- **Aux power by outside temperature (block 7):** needs patch points for its values.
- **AC rates:** an AC session can't tell the car's on-board charger from the wall unit's limit.

## On-car learning (DC charge curve)

`ChargeLearn` (gpm-ev/src) learns how fast this car DC-charges, per 10 % SOC band, and the sender turns it into the 4.1 curve and the 8.1 tables Maps gets.
- **Input:** EvDriveLog's 5 s rows during DC charging (`fast_charging_raw`). Every 60 s window gives the battery-side power from the BMS energy, made charger-side (÷ `CHARGE_ETA`) like the curve, and divided by the model's power at that SOC: a ratio.
- **Only where the car set the pace:**
  - the charger's power must be known: `nav_charger_kw`, the charger Maps was guiding to, seen up to 10 min before plugging in. An unguided session teaches nothing, so a slow post never makes the car look slow;
  - the window ran below 0.9 × that power;
  - not a Tesla Supercharger row (250/325 kW: the boost converter's flat ~97 kW, already in the model);
  - not the first 3 min (the ramp), not a pack below 20 °C (the model is the preconditioned curve; unknown counts as warm).
- **Per session:** windows below 0.6 × the session's median are dropped (a charger's brief dip). Each band with 3 or more windows gives its median as one sample, and the median of all windows is one sample of the car's overall scale.
- **Scale per band:** (PRIOR × g + the band's samples) ÷ (PRIOR + n), with g the overall scale learned the same way from 1.0. A band never charged in follows the car's overall scale. PRIOR is 2 sessions on E-GMP (the model was fitted on E-GMP stops) and 0.5 on other EVs, whose 400 V packs charge far slower. Clamped to 0.25–1.2.
- **Use:** the sender reads the curve once per Android Auto session, writes its powers into 4.1 (`POWER_OFFSETS`) and integrates the 8.1 rows from it on the head unit (`ChargeCurve`, the generator's `charge_table()`; for the model's own curve it gives the generator's seconds exactly), then scales energies and seconds by capacity as before. The AC rows follow the curve only where it falls below the charger near full.
- **Storage and visibility:** `charge_curve` in GPM's files dir; the log header shows `charge_scale` (per band) and `charge_sessions`. Same setting as road load learning.

Replaying the 2022 EV6's logs (`EV_LOGS=<folder> sh gpm-ev/test/run.sh`, ChargeLearnTest): all three of its DC stops were guided by Maps (two at 150 kW posts, mostly charger-limited, one at 350 kW). They give 0.95–0.96 at 50–80 % and 0.97 overall. For its 44 → 82 % stop at a 350 kW charger (13.4 min real), Maps' time goes from 10.6 min (model) to 11.1 min. The rest is the 40–50 % band, which these stops only reached during the ramp, and the ramp itself, which Maps' tables can't carry: they are differences of time-to-full.

## Next steps
1. ~~Recover the real schema from Google Maps~~ (done, see above).
2. ~~Write the generator~~ (done: `tools/build_ioniq5_model.py`; shipped values below are its constants, verified accepted by Maps on the car 2026-10-28).
3. Test on the car: ~~does Maps accept the model~~ (yes). Charge tables calibrated against 233 real EA sessions (`tools/analyze_charge_history.py`, `reference/charge_history*.csv`): mean err −1.0 min, MAE 3.1 min. Exact-start spot pulls (EA app) validated: `CHARGE_ETA=0.95` (warm sessions imply 0.944–0.958; EA energy is charger-side, battery gets ~5% less), On 2026-10-29 the top end was replaced by the car's own estimate (80→90% 8 min, 90→100% 18 min at 62.9 kWh, no separate thermal pause; see "Field findings"). After that change: mean error −1.3 min, MAE 2.7 min over the same 233 sessions. Usable capacity ≈ 77.4 kWh when new (2022 sessions) — Hyundai's figure is usable, not gross; on the car degradation is handled by learning the capacity from BMS energy ÷ SOC (SOH is only logged). Remaining variance: pack not-quite-preconditioned sessions (preconditioning state matters, not season). Do range and arrival-SOC predictions match real drives? Then tune blocks 3 and 7.
4. Optional, to pin down the unknown fields: capture a model from a real AAOS/OEM car (e.g. a `content://google_maps_energy/model` dump), or A/B test `1.9`/`1.10` by watching how Maps' charging-stop durations change.

## Sources
- Google Maps 26.43.00 (APKMirror bundle in `private/maps-26.43/`): `cdim` (wire schema), `gfz.q` (converter), `qis` (zero-energy gate), `qft` (re-route thresholds), `bdjr`/`qgr` (demo models), `pxl`/`pxo` (sensor 23 parse)
- aa-proxy-rs `src/protos/ford_ev_model.bin`, `src/ev.rs`, `src/protos/ev.proto`: https://github.com/aa-proxy/aa-proxy-rs
- aa-proxy EV docs: https://aa-proxy.github.io/docs/ev. The EV Model Generator it links (http://152.70.21.153/index.php) returned 404 on 2026-10-27
- open-android-auto (sensor 23/25 and VehicleEnergyForecast protos): https://github.com/Deadknight/open-android-auto
- ArdaMapsEnergyProvider (the same model used on AAOS): https://github.com/Deadknight/ArdaMapsEnergyProvider
- aa-proxy-rs issue #19 (early attempts; fuel type and connectors alone didn't trigger EV routing): https://github.com/aa-proxy/aa-proxy-rs/issues/19

## Winter add-on and the block-4 test (test settings, 2026-11-21)

**`winter_model`** (off by default) appends blocks Ford's sample doesn't have. They go after the model in the same sensor-23 message, and protobuf merges the second block 8 into the first. `EvModelSender.withWinter` rewrites the 2-byte length. The model plus the add-on is about 12.5 KB.
- **6** `auxiliary_drain_at_time[0]`: the live climate power (HEV 0x4f × 20 W) plus the battery heater (HEV 0x43, W), in 100 W steps. A change of 400 W or more has to hold for 60 s before it is sent, because Maps re-routes when `6[0]` moves by 500 W or more.
- **8.2** `charging_curves_at_temperature`: the DC rows (350–50 kW, the Supercharger rows included), 13 points each, capped at the cold-pack limits from the logbook: 5 °C → 90 kW, 12 °C → 120 kW, 18 °C → 160 kW, and 22 °C → the warm curve. Maps picks a set by `1.8`, the live pack temperature. They are scaled to the learned capacity like 8.1. The learned charge curve isn't applied to them, because they are caps.
- **10** `battery_preconditioning`: **assumed** E-GMP values, nothing measured:
  - target pack temperature 15 / 22 / 25 °C at 50 / 150 / 350 kW;
  - a 5 kW heater;
  - 120 Wh per °C above 5 °C;
  - no preconditioning under 7.7 kWh (about 10 %);
  - at most 5–8 °C of rise while charging.

**`block4_flat_test`** (off) sets every 4.1 power to 150 kW after 8.1 has been integrated from the real curve. If Maps' planned charging minutes move, Maps reads 4.1 as a charge limit. If they don't, it is the regen limit, as v1.2 says.

Host tests: `hmgapi-mod/test/WinterTest.java`. protoc decodes the model plus the add-on with the blocks merged: 16 charger rows and 4 temperature sets. Not yet on a car.
